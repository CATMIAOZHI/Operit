package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.ai.assistance.operit.util.AppLogger
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumChromeClient
import com.cefrium.CefriumConsoleMessage
import com.cefrium.CefriumContentRequest
import com.cefrium.CefriumContentResponse
import com.cefrium.CefriumDownloadClient
import com.cefrium.CefriumFileChooserCallback
import com.cefrium.CefriumFileChooserParams
import com.cefrium.CefriumJsDialogType
import com.cefrium.CefriumLoadError
import com.cefrium.CefriumNavigationClient
import com.cefrium.CefriumNavigationRequest
import com.cefrium.CefriumPermission
import com.cefrium.CefriumPermissionRequest
import com.cefrium.CefriumProfile
import com.cefrium.CefriumRenderHandler
import com.cefrium.CefriumRendererTerminationStatus
import com.cefrium.CefriumResourceInterceptor
import com.cefrium.CefriumSslError
import com.cefrium.CefriumTextInputMode
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.WeakHashMap
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

private const val PAGE_TAG = "CefriumBrowserPage"

/**
 * A tab on Cefrium, off screen: the engine runs the page without a window and hands its frames
 * to [view], which shows them and passes touches, keys and the keyboard's text back. So a tab
 * keeps running in the background like any page, and the user can still take it over.
 * Main thread, like the engine.
 */
internal class CefriumBrowserPage private constructor(
    context: Context,
    private val browser: CefriumBrowser,
    /** Device pixels per CSS pixel the engine was created with. */
    val deviceScale: Float,
) : EngineBrowserPage {
    @Volatile private var closed = false
    // Read on the engine's network threads too, for requests.
    @Volatile override var events: BrowserPageEvents? = null
    override val view: CefriumPageView = CefriumPageView(context, this)

    private var agent = ""
    /** Whether the client hints say desktop, mobile, or nothing yet. */
    private var hintsDesktop: Boolean? = null
    private val messageListeners = HashMap<String, (String, Boolean, BrowserMessageReply) -> Unit>()

    /** Document-start scripts in the order added; re-added after a message channel so the channel exists first. */
    private class StartScript(val script: String, var handler: CefriumBrowser.ScriptHandler?)
    private val startScripts = mutableListOf<StartScript>()

    /**
     * DevTools calls wait here until the page's first load starts. The engine (Cefrium 0.21)
     * holds calls made before the browser exists until then, but sends calls made just after it
     * exists at once; a call in that gap overtakes the held ones, DevTools renumbers them, and
     * from then on every call of the tab is refused with "the method was not accepted" and
     * replies reach the wrong callers. Sending nothing before the first load start avoids it. A
     * first load that never starts (refused, failed early, turned into a download) still opens
     * them once it stops: the browser exists by then, and the engine holds nothing of ours.
     */
    private var devToolsOpen = false
    private class HeldCall(val method: String, val params: String, val callback: CefriumBrowser.DevToolsCallback?)
    private val heldDevToolsCalls = ArrayList<HeldCall>()

    /**
     * Calls handed to the engine that it has not answered yet. The engine keeps each one's callback
     * until the answer or the tab's end, even after the caller gave up, so past a limit new calls
     * are refused instead of piling up. A slot frees only with the engine's answer. Main thread.
     */
    private var unansweredDevToolsCalls = 0

    /**
     * A page's window (from [onCreateWindow]): the engine also holds its setup (the script channel,
     * frames) until its first page commits. One whose first load ends without a page (a download,
     * a refused link) loads a blank page once, so the setup arrives.
     */
    private var isWindow = false
    private var loadStarted = false
    private var sawLoading = false
    private var blankLoaded = false

    /** Characters of frame answers handed to [queryReader] and not sent yet; they count toward the tab's limit. */
    private val encodingChars = java.util.concurrent.atomic.AtomicLong()
    /** Changes with each new document and at close: answers written for an older one are not sent. */
    @Volatile private var documentGeneration = 0

    /**
     * Answers for frames inside the page, by channel and frame: the engine can only answer a
     * frame's own query, so each such frame keeps one "listen" query open and gets its answers in
     * batches through it. Main thread.
     */
    private class FrameInbox {
        var waiting: CefriumBrowser.QueryCallback? = null
        val queued = ArrayDeque<String>()
        var queuedChars = 0L
    }
    private val frameInboxes = LinkedHashMap<String, FrameInbox>()

    init {
        pages[browser] = this
        browser.setRenderHandler(renderHandler())
        browser.setNavigationClient(navigationClient())
        browser.setChromeClient(chromeClient())
        browser.setDownloadClient(downloadClient())
        // No request interceptor until the tab has request rules (interceptsRequests).
        browser.setDevToolsEventListener { method, params -> devTools.eventListener?.invoke(method, params) }
        browser.setQueryHandler(CefriumBrowser.QueryOriginPolicy.anyOrigin().withSubframes(), queryHandler())
        // The app's own script objects, before any page script, as the WebView's JavaScript interfaces.
        browser.addDocumentStartJavaScript(BRIDGE_SCRIPT, setOf("*"))
        // Pages count as focused, as in a WebView: some refuse to work otherwise (the clipboard, for one).
        browser.setFocus(true)
    }

    companion object {
        /** Tabs by engine browser, to find the one a page's window belongs to. Main thread. */
        private val pages = WeakHashMap<CefriumBrowser, CefriumBrowserPage>()

        fun create(context: Context, profile: CefriumProfile): CefriumBrowserPage {
            val metrics = context.resources.displayMetrics
            val browser =
                CefriumBrowser(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1), metrics.density, profile)
            return try {
                CefriumBrowserPage(context, browser, metrics.density)
            } catch (e: Throwable) {
                runCatching { browser.close() }
                throw e
            }
        }

        private val main = Handler(Looper.getMainLooper())

        /** Reads the page's calls off the main thread: a download hands over the whole file as base64. */
        private val queryReader = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "operit-engine-query").apply { isDaemon = true }
        }

        private const val MAX_FRAME_INBOXES = 64
        private const val MAX_QUEUED_PER_FRAME = 1_000
        /** Characters waiting for frames inside the page: per frame, and for the whole tab. */
        private const val MAX_QUEUED_CHARS_PER_FRAME = 4_000_000L
        private const val MAX_QUEUED_CHARS_PER_TAB = 16_000_000L
        private const val MAX_UNANSWERED_DEVTOOLS = 64

        /**
         * Calls on the app's script objects go to the app through the engine's query channel. The
         * channel is looked up on each call: at document start it may not be there yet.
         */
        private val BRIDGE_SCRIPT =
            """
            (function() {
                if (window.__operitBridgeReady) return;
                Object.defineProperty(window, '__operitBridgeReady', { value: true });
                var send = function(bridge, method, args) {
                    try {
                        window.cefriumQuery({ request: JSON.stringify({ b: bridge, m: method, a: args }), onSuccess: function() {}, onFailure: function() {} });
                    } catch (e) {}
                };
                var define = function(name, methods) {
                    var object = {};
                    methods.forEach(function(method) {
                        object[method] = function() { send(name, method, Array.prototype.slice.call(arguments)); };
                    });
                    try { Object.defineProperty(window, name, { value: Object.freeze(object) }); } catch (e) {}
                };
                define('OperitWebDownloadBridge', ['downloadBase64', 'log']);
                define('OperitAsyncBridge', ['resolve', 'reject']);
                define('OperitTextSelectionBridge', ['showActions', 'performHapticFeedback', 'hideActions']);
            })();
            """.trimIndent()

        /**
         * `window.<name>` with postMessage and onmessage, like WebView's web message listener. In a
         * frame inside the page, the first message also starts a "listen" query that brings the
         * app's answers (see [frameInboxes]).
         */
        private fun channelScript(name: String): String {
            val quoted = JSONObject.quote(name)
            return """
            (function() {
                var name = $quoted;
                if (window[name]) return;
                var target = new EventTarget();
                var inFrame = true;
                try { inFrame = window.top !== window; } catch (e) {}
                var frameId = inFrame ? Date.now().toString(36) + Math.random().toString(36).slice(2) : '';
                var listening = 0;
                var generation = 0;
                var query = function(method, args, onSuccess, onFailure) {
                    try {
                        window.cefriumQuery({ request: JSON.stringify({ b: 'msg:' + name, m: method, a: args, f: frameId }), onSuccess: onSuccess || function() {}, onFailure: onFailure || function() {} });
                        return true;
                    } catch (e) { return false; }
                };
                var listen = function() {
                    var mine = ++generation;
                    listening = mine;
                    var sent = query('listen', [], function(response) {
                        if (listening !== mine) return;
                        try { JSON.parse(response).forEach(function(data) { channel.__deliver(data); }); } catch (e) {}
                        listen();
                    }, function() { if (listening === mine) listening = 0; });
                    if (!sent) listening = 0;
                };
                var channel = {
                    onmessage: null,
                    postMessage: function(data) {
                        query('postMessage', [String(data)]);
                        if (inFrame && !listening) listen();
                    },
                    addEventListener: function(type, listener) { target.addEventListener(type, listener); },
                    removeEventListener: function(type, listener) { target.removeEventListener(type, listener); }
                };
                Object.defineProperty(channel, '__deliver', {
                    value: function(data) {
                        var event = new MessageEvent('message', { data: data });
                        if (typeof channel.onmessage === 'function') channel.onmessage(event);
                        target.dispatchEvent(event);
                    }
                });
                Object.defineProperty(window, name, { value: channel });
            })();
            """.trimIndent()
        }
    }

    override val url: String? get() = if (closed) null else browser.url
    override val title: String? get() = if (closed) null else browser.title
    override val progress: Int get() = if (closed) 0 else browser.progress
    // Pinch zoom inside the page is not followed; the AI's coordinates come from the page itself.
    override val scale: Float get() = deviceScale
    override val canRunScripts: Boolean get() = !closed

    override var userAgent: String
        get() = agent
        set(value) {
            agent = value
            applyUserAgent()
        }

    override fun applyChromeClientHints(desktop: Boolean) {
        hintsDesktop = desktop
        applyUserAgent()
    }

    /**
     * The user agent and, once known, client hints that name Chrome (the engine itself adds
     * "Cefrium" and leaves out the Google Chrome brand). One DevTools call sets both, so neither
     * undoes the other.
     */
    private fun applyUserAgent() {
        if (closed || agent.isBlank()) return
        browser.setUserAgentOverride(agent)
        val desktop = hintsDesktop ?: return
        val full = CefriumEngine.chromeFullVersion
        val major = full.substringBefore('.')
        val brands = JSONArray()
        val fullList = JSONArray()
        listOf("Chromium" to major, "Google Chrome" to major, "Not.A/Brand" to "99").forEach { (brand, version) ->
            brands.put(JSONObject().put("brand", brand).put("version", version))
            fullList.put(JSONObject().put("brand", brand).put("version", if (brand == "Not.A/Brand") "99.0.0.0" else full))
        }
        val metadata =
            JSONObject()
                .put("brands", brands)
                .put("fullVersionList", fullList)
                .put("fullVersion", full)
                // Desktop: what Chrome on Android sends for "Desktop site", matching the user agent.
                .put("platform", if (desktop) "Linux" else "Android")
                .put("platformVersion", if (desktop) "" else Build.VERSION.RELEASE.orEmpty())
                .put("architecture", if (desktop) "x86" else "")
                .put("model", if (desktop) "" else Build.MODEL.orEmpty())
                .put("mobile", !desktop)
                .put("bitness", if (desktop) "64" else "")
                .put("wow64", false)
        callDevTools(
            "Emulation.setUserAgentOverride",
            JSONObject().put("userAgent", agent).put("userAgentMetadata", metadata).toString()
        ) { ok, json -> if (!ok) AppLogger.w(PAGE_TAG, "Client hints were not set: $json") }
    }

    /**
     * A DevTools call now, or once the first load has started (see [heldDevToolsCalls]). The
     * returned function drops a held call that was not sent yet. Main thread.
     */
    private fun callDevTools(method: String, paramsJson: String, callback: CefriumBrowser.DevToolsCallback?): () -> Unit {
        when {
            closed -> callback?.onDevToolsResult(false, CLOSED_JSON)
            devToolsOpen -> sendDevTools(method, paramsJson, callback)
            else -> {
                val call = HeldCall(method, paramsJson, callback)
                heldDevToolsCalls += call
                return { heldDevToolsCalls.remove(call) }
            }
        }
        return {}
    }

    private fun openDevTools() {
        if (devToolsOpen || closed) return
        devToolsOpen = true
        val calls = heldDevToolsCalls.toList()
        heldDevToolsCalls.clear()
        calls.forEach { sendDevTools(it.method, it.params, it.callback) }
    }

    private fun sendDevTools(method: String, paramsJson: String, callback: CefriumBrowser.DevToolsCallback?) {
        if (unansweredDevToolsCalls >= MAX_UNANSWERED_DEVTOOLS) {
            callback?.onDevToolsResult(false, BUSY_JSON)
            return
        }
        unansweredDevToolsCalls++
        var answered = false
        browser.executeDevToolsMethod(method, paramsJson) { ok, json ->
            if (!answered) {
                answered = true
                unansweredDevToolsCalls--
            }
            callback?.onDevToolsResult(ok, json)
        }
    }

    override var interceptsRequests: Boolean = false
        set(value) {
            if (field == value || closed) return
            field = value
            browser.setResourceInterceptor(if (value) resourceInterceptor() else null)
        }

    override fun loadUrl(url: String, headers: Map<String, String>) {
        if (closed) return
        if (headers.isEmpty()) browser.loadUrl(url) else browser.loadUrl(url, headers)
    }

    override fun canGoBack() = !closed && browser.canGoBack()
    override fun canGoForward() = !closed && browser.canGoForward()
    override fun goBack() { if (!closed) browser.goBack() }
    override fun goForward() { if (!closed) browser.goForward() }
    override fun canGoBackOrForward(steps: Int) = !closed && browser.canGoBackOrForward(steps)
    override fun goBackOrForward(steps: Int) { if (!closed) browser.goBackOrForward(steps) }

    override fun backForwardList(): BrowserBackForwardList {
        if (closed) return BrowserBackForwardList.EMPTY
        val entries = browser.navigationEntries
        if (entries.isEmpty()) return BrowserBackForwardList.EMPTY
        return BrowserBackForwardList(
            entries.map { BrowserBackForwardList.Entry(it.url.orEmpty(), it.title.orEmpty()) },
            entries.indexOfFirst { it.current }.takeIf { it >= 0 } ?: (entries.size - 1),
        )
    }

    override fun reload() { if (!closed) browser.reload() }
    override fun stopLoading() { if (!closed) browser.stopLoading() }

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        if (closed) {
            callback?.invoke(null)
            return
        }
        // The app's script objects first: a page loaded before the document-start script was in
        // place (a new window's first page) has none. Adding them again changes nothing.
        val withBridge = BRIDGE_SCRIPT + "\n" + script
        if (callback == null) {
            browser.evaluateJavaScript(withBridge)
            return
        }
        // As the engine's evaluateJavaScript with a callback (Runtime.evaluate), held like any DevTools call.
        val params = JSONObject().put("expression", withBridge).put("returnByValue", true).toString()
        callDevTools("Runtime.evaluate", params) { ok, json -> callback(if (ok) evaluateResultValue(json) else null) }
    }

    override fun addDocumentStartScript(script: String): BrowserScriptHandle? {
        if (closed) return null
        val entry = StartScript(script, runCatching { browser.addDocumentStartJavaScript(script, setOf("*")) }.getOrNull() ?: return null)
        startScripts += entry
        return BrowserScriptHandle {
            startScripts.remove(entry)
            if (!closed) entry.handler?.remove()
            entry.handler = null
        }
    }

    override fun addMessageListener(
        name: String,
        onMessage: (message: String, isMainFrame: Boolean, reply: BrowserMessageReply) -> Unit,
    ): Boolean {
        if (closed) return false
        val fresh = name !in messageListeners
        messageListeners[name] = onMessage
        if (fresh) {
            runCatching { browser.addDocumentStartJavaScript(channelScript(name), setOf("*")) }.getOrElse { return false }
            // The scripts added earlier read the channel at document start: run them after it.
            startScripts.forEach { entry ->
                entry.handler?.remove()
                entry.handler = runCatching { browser.addDocumentStartJavaScript(entry.script, setOf("*")) }.getOrNull()
            }
        }
        return true
    }

    override fun isAudioMuted(): Boolean? = if (closed) null else browser.isAudioMuted

    override fun setAudioMuted(muted: Boolean): Boolean {
        if (closed) return false
        browser.setAudioMuted(muted)
        return true
    }

    override fun captureVisible(): Bitmap? = view.copyFrame()

    // Hides the page and holds its media without touching the tab's mute, as WebView's onPause does.
    override fun onPause() {
        if (closed) return
        browser.setPageVisible(false)
        browser.suspendMedia()
    }

    override fun onResume() {
        if (closed) return
        browser.setPageVisible(true)
        browser.resumeMedia()
    }

    override fun destroy() {
        if (closed) return
        closed = true
        documentGeneration++
        events = null
        pages.remove(browser)
        val held = heldDevToolsCalls.toList()
        heldDevToolsCalls.clear()
        held.forEach { it.callback?.onDevToolsResult(false, CLOSED_JSON) }
        frameInboxes.values.forEach { inbox -> inbox.waiting?.failure(-1, "the tab is closed") }
        frameInboxes.clear()
        view.release()
        runCatching { browser.close() }.onFailure { AppLogger.w(PAGE_TAG, "Closing the tab failed: ${it.message}") }
    }

    override val devTools: BrowserDevTools =
        object : BrowserDevTools {
            override var eventListener: ((String, String) -> Unit)? = null

            override fun execute(method: String, paramsJson: String, onResult: ((Boolean, String) -> Unit)?): () -> Unit =
                callDevTools(method, paramsJson, onResult?.let { done ->
                    CefriumBrowser.DevToolsCallback { ok, json -> done(ok, json ?: "{}") }
                })

            override fun requestFrame() {
                if (!closed) browser.invalidate()
            }
        }

    /* Calls from the view. */

    internal val isClosed: Boolean get() = closed

    internal fun resize(width: Int, height: Int) {
        if (!closed && width > 0 && height > 0) browser.setSize(width, height)
    }

    internal fun touch(action: Int, x: Float, y: Float, pointerId: Int) {
        if (!closed) browser.sendTouchEvent(action, x / deviceScale, y / deviceScale, pointerId)
    }

    internal fun key(event: android.view.KeyEvent) {
        if (!closed) browser.sendKeyEvent(event)
    }

    internal fun imeComposition(text: String, selStart: Int, selEnd: Int) {
        if (!closed) browser.imeSetComposition(text, selStart, selEnd)
    }

    internal fun imeCommit(text: String, newCursorPosition: Int) {
        if (!closed) browser.imeCommitText(text, newCursorPosition)
    }

    internal fun imeFinish() {
        if (!closed) browser.imeFinishComposingText(false)
    }

    /**
     * The keyboard moves the cursor or selects in the focused field (the engine has no call for
     * it): an input or text area by its value, an editable block (contenteditable) by its text.
     * Run in the focused frame, so a field inside an iframe is reached too.
     */
    internal fun selectInField(start: Int, end: Int) {
        if (closed) return
        val from = minOf(start, end).coerceAtLeast(0)
        val to = maxOf(start, end).coerceAtLeast(0)
        browser.evaluateJavaScriptInFocusedFrame(
            """
            (function(){
              var e=document.activeElement;
              while(e&&e.shadowRoot&&e.shadowRoot.activeElement)e=e.shadowRoot.activeElement;
              if(!e)return;
              try{if(typeof e.setSelectionRange==='function'){e.setSelectionRange($from,$to);return;}}catch(x){}
              if(!e.isContentEditable)return;
              var root=e;
              while(root.parentElement&&root.parentElement.isContentEditable)root=root.parentElement;
              var walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null),node,seen=0,startAt=null,endAt=null,last=null;
              while((node=walker.nextNode())){
                var len=node.nodeValue.length;last=node;
                if(!startAt&&$from<=seen+len)startAt=[node,$from-seen];
                if(!endAt&&$to<=seen+len){endAt=[node,$to-seen];break;}
                seen+=len;
              }
              var range=document.createRange();
              if(!last){range.setStart(root,0);range.collapse(true);}
              else{
                startAt=startAt||[last,last.nodeValue.length];endAt=endAt||[last,last.nodeValue.length];
                range.setStart(startAt[0],startAt[1]);range.setEnd(endAt[0],endAt[1]);
              }
              var selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);
            })()
            """.trimIndent()
        )
    }

    /** Where the engine shows its own pickers (a select's options, dates): the activity the tab is in, if any. */
    internal fun setDialogActivity(activity: Activity?) {
        if (!closed) browser.setDialogActivity(activity)
    }

    /* The engine's callbacks. */

    private fun renderHandler() =
        object : CefriumRenderHandler {
            override fun onPaint(width: Int, height: Int, pixels: ByteBuffer) = view.onFrame(width, height, pixels)

            override fun onVirtualKeyboardRequested(mode: CefriumTextInputMode) =
                view.onKeyboardRequested(mode != CefriumTextInputMode.NONE, mode.name)

            override fun onFocusedNodeChanged(editable: Boolean, value: String, placeholder: String, type: String) =
                view.onFocusedField(editable, type)

            override fun onQuickMenu(x: Int, y: Int, width: Int, height: Int, menu: CefriumBrowser.QuickMenuCallback) =
                view.showQuickMenu(x, y, height, menu)

            override fun onPopupShow(show: Boolean) = view.onPopupShow(show)

            override fun onPopupSize(x: Int, y: Int, width: Int, height: Int) = view.onPopupBounds(x, y, width, height)

            override fun onPopupPaint(x: Int, y: Int, width: Int, height: Int, pixels: ByteBuffer) =
                view.onPopupFrame(x, y, width, height, pixels)
        }

    private fun navigationClient() =
        object : CefriumNavigationClient {
            override fun onBeforeNavigate(browser: CefriumBrowser, request: CefriumNavigationRequest): Boolean =
                request.isForMainFrame && events?.shouldOverrideNavigation(request.url) == true

            override fun onLoadStart(browser: CefriumBrowser, url: String) {
                // The blank page a stuck window loads (see isWindow) is not a page of its own: the
                // window keeps the failure it had, its console and certificate error included.
                val internalBlank = blankLoaded && !loadStarted
                loadStarted = true
                // The engine has sent the calls it held for this moment; ours can follow.
                openDevTools()
                // A new document: the frames that waited for answers are gone.
                frameInboxes.clear()
                documentGeneration++
                if (!internalBlank) events?.onPageStarted(url)
            }

            override fun onUrlChanged(browser: CefriumBrowser, url: String) {
                events?.onUrlChanged(url)
            }

            override fun onLoadingStateChanged(browser: CefriumBrowser, loading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
                if (closed) return
                if (loading) {
                    sawLoading = true
                    return
                }
                // A first load that stopped without starting (see heldDevToolsCalls).
                openDevTools()
                if (isWindow && sawLoading && !loadStarted && !blankLoaded) {
                    blankLoaded = true
                    browser.loadUrl("about:blank")
                }
                events?.onPageFinished(browser.url.orEmpty())
            }

            override fun onLoadError(browser: CefriumBrowser, error: CefriumLoadError) {
                openDevTools()
                // DevTools may not have been listening yet (a window's first load); the console keeps it.
                // -3 is a load the browser itself stopped, as for a download.
                if (error.errorCode != -3) {
                    events?.onConsoleMessage(
                        "error",
                        "Failed to load ${error.failedUrl.orEmpty()}: ${error.description.orEmpty()} (net error ${error.errorCode})",
                        error.failedUrl,
                        null,
                    )
                }
            }

            override fun onRenderProcessTerminated(browser: CefriumBrowser, status: CefriumRendererTerminationStatus, errorCode: Int) {
                events?.onRenderProcessGone(
                    status == CefriumRendererTerminationStatus.CRASHED || status == CefriumRendererTerminationStatus.ABNORMAL
                )
            }

            override fun onSslError(browser: CefriumBrowser, error: CefriumSslError, callback: CefriumBrowser.SslErrorCallback) {
                // Going on would let anyone on the network read or change the page, logins included.
                callback.cancel()
                val reason =
                    when (error.errorCode) {
                        -200 -> "the certificate is for a different site"
                        -201 -> "the certificate has expired or is not valid yet"
                        -202 -> "the certificate is not from a trusted authority"
                        else -> "the certificate is invalid"
                    }
                events?.onSslError(error.url.orEmpty(), reason)
            }
        }

    private fun chromeClient() =
        object : CefriumChromeClient {
            override fun onTitleChanged(browser: CefriumBrowser, title: String) {
                events?.onTitleChanged(title)
            }

            override fun onConsoleMessage(browser: CefriumBrowser, message: CefriumConsoleMessage) {
                val level =
                    when (message.level()) {
                        CefriumConsoleMessage.Level.VERBOSE -> "debug"
                        CefriumConsoleMessage.Level.INFO -> "log"
                        CefriumConsoleMessage.Level.WARNING -> "warning"
                        else -> "error"
                    }
                events?.onConsoleMessage(level, message.message().orEmpty(), message.sourceId(), message.lineNumber())
            }

            override fun onJsDialog(
                browser: CefriumBrowser,
                type: CefriumJsDialogType,
                message: String,
                defaultText: String,
                callback: CefriumBrowser.JsDialogCallback,
            ): Boolean {
                val listener = events ?: return false
                val kind =
                    when (type) {
                        CefriumJsDialogType.ALERT -> "alert"
                        CefriumJsDialogType.CONFIRM -> "confirm"
                        else -> "prompt"
                    }
                listener.onJsDialog(kind, message, defaultText.takeIf { kind == "prompt" }, url) { ok, text ->
                    callback.continueDialog(ok, text.orEmpty())
                }
                return true
            }

            // "Leave this page?" for unsaved changes: the engine would otherwise leave without asking.
            override fun onBeforeUnloadDialog(
                browser: CefriumBrowser,
                message: String,
                isReload: Boolean,
                callback: CefriumBrowser.JsDialogCallback,
            ): Boolean {
                val listener = events ?: return false
                // Pages' own texts are ignored by browsers; say what OK and Cancel do.
                val question = view.context.getString(com.ai.assistance.operit.R.string.web_session_before_unload_message)
                listener.onJsDialog("confirm", question, null, url) { leave, _ -> callback.continueDialog(leave, "") }
                return true
            }

            override fun onShowFileChooser(
                browser: CefriumBrowser,
                params: CefriumFileChooserParams,
                callback: CefriumFileChooserCallback,
            ): Boolean {
                val listener = events ?: return false
                val mode = params.mode
                // Folders and "save as" go to the engine's own picker.
                if (mode != CefriumFileChooserParams.Mode.OPEN && mode != CefriumFileChooserParams.Mode.OPEN_MULTIPLE) return false
                listener.onFileChooser(params.acceptTypes, mode == CefriumFileChooserParams.Mode.OPEN_MULTIPLE) { uris ->
                    if (uris.isNullOrEmpty()) callback.cancel() else callback.select(uris.map { it.toString() }.toTypedArray())
                }
                return true
            }

            override fun onCreateWindow(browser: CefriumBrowser, popup: CefriumBrowser, url: String, userGesture: Boolean): Boolean {
                val listener = events ?: return false
                val page = CefriumBrowserPage(view.context, popup, deviceScale)
                page.isWindow = true
                if (listener.onCreateWindow(page, url)) return true
                // Refused: the engine closes the window itself.
                page.closed = true
                pages.remove(popup)
                return false
            }

            override fun onCloseWindow(browser: CefriumBrowser, popup: CefriumBrowser) {
                // Through the window's own tab: its opener may be closed already.
                pages[popup]?.let { page -> page.events?.onCloseWindow(page) }
            }

            override fun onPermissionRequest(browser: CefriumBrowser, request: CefriumPermissionRequest) {
                val listener = events ?: return request.deny()
                val kinds =
                    request.permissions.map { permission ->
                        when (permission) {
                            CefriumPermission.CAMERA -> "camera"
                            CefriumPermission.MICROPHONE -> "microphone"
                            CefriumPermission.GEOLOCATION -> "location"
                            else -> permission.name.lowercase()
                        }
                    }.toSet()
                listener.onPermissionRequest(request.origin.orEmpty(), kinds) { granted ->
                    if (granted) request.grant() else request.deny()
                }
            }
        }

    /** The app's download list fetches files itself, with the tab's cookies. */
    private fun downloadClient() =
        object : CefriumDownloadClient {
            override fun onBeforeDownload(
                browser: CefriumBrowser,
                item: CefriumBrowser.DownloadItem,
                callback: CefriumBrowser.BeforeDownloadCallback,
            ) {
                callback.cancel()
                events?.onDownload(item.url.orEmpty(), item.mimeType, item.suggestedName, item.totalBytes)
            }
        }

    /** Userscripts' webRequest rules may block, redirect or rewrite a request, as on the WebView. */
    private fun resourceInterceptor() =
        object : CefriumResourceInterceptor {
            override fun shouldInterceptRequest(request: CefriumContentRequest): CefriumContentResponse? {
                val handler = events ?: return null
                if (closed) return null
                val answer = runCatching { handler.interceptRequest(EngineWebRequest(request)) }
                    .onFailure { AppLogger.w(PAGE_TAG, "Request rule failed: ${it.message}") }
                    .getOrNull() ?: return null
                return answer.toEngineResponse()
            }
        }

    private fun queryHandler() =
        object : CefriumBrowser.QueryHandler {
            override fun onQuery(queryId: Long, request: String, origin: String, callback: CefriumBrowser.QueryCallback): Boolean =
                onQuery(queryId, request, origin, true, callback)

            override fun onQuery(
                queryId: Long,
                request: String,
                origin: String,
                isMainFrame: Boolean,
                callback: CefriumBrowser.QueryCallback,
            ): Boolean {
                queryReader.execute { readQuery(request, isMainFrame, callback) }
                return true
            }
        }

    /** Reader thread; the channels' state is the main thread's. */
    private fun readQuery(request: String, isMainFrame: Boolean, callback: CefriumBrowser.QueryCallback) {
        val call = runCatching { JSONObject(request) }.getOrNull()
        val bridge = call?.optString("b").orEmpty()
        if (call == null || !bridge.startsWith("msg:")) {
            callback.success("")
            if (call != null) events?.onScriptBridge(bridge, call.optString("m"), call.optJSONArray("a") ?: JSONArray())
            return
        }
        val name = bridge.removePrefix("msg:")
        // A frame inside the page names itself; the main frame is answered through the page.
        val frame = if (isMainFrame) null else call.optString("f").ifBlank { null }
        val listen = call.optString("m") == "listen"
        if (!listen) callback.success("")
        val text = call.optJSONArray("a")?.optString(0).orEmpty()
        main.post {
            when {
                closed -> if (listen) callback.failure(-1, "the tab is closed")
                listen -> if (frame == null) callback.success("[]") else frameInbox(name, frame).let { inbox ->
                    // A newer listen of the same frame replaces the old; the old one stops.
                    inbox.waiting?.failure(-1, "replaced")
                    inbox.waiting = callback
                    flush(inbox)
                }
                else -> {
                    val listener = messageListeners[name] ?: return@post
                    if (!isMainFrame && frame == null) return@post
                    val reply = BrowserMessageReply { answer -> replyTo(name, answer, frame) }
                    listener(text, isMainFrame, reply)
                }
            }
        }
    }

    private fun frameInbox(name: String, frame: String): FrameInbox {
        val key = name + "\n" + frame
        frameInboxes[key]?.let { return it }
        // Frames that came and went within one document: the oldest goes.
        if (frameInboxes.size >= MAX_FRAME_INBOXES) {
            frameInboxes.entries.first().let { (oldKey, old) ->
                old.waiting?.failure(-1, "too many frames")
                frameInboxes.remove(oldKey)
            }
        }
        return FrameInbox().also { frameInboxes[key] = it }
    }

    private fun flush(inbox: FrameInbox) {
        val waiting = inbox.waiting ?: return
        if (inbox.queued.isEmpty()) return
        inbox.waiting = null
        val batch = inbox.queued.toList()
        val chars = inbox.queuedChars
        inbox.queued.clear()
        inbox.queuedChars = 0
        encodingChars.addAndGet(chars)
        val generation = documentGeneration
        // Written off the main thread, as a frame may get large answers. ASCII only: the engine
        // reads the answer as modified UTF-8, which would garble emoji.
        queryReader.execute {
            try {
                if (closed || generation != documentGeneration) waiting.failure(-1, "the page is gone")
                else waiting.success(asciiJson(JSONArray(batch).toString()))
            } finally {
                encodingChars.addAndGet(-chars)
            }
        }
    }

    /**
     * Drops the oldest answers while the tab holds too much, those waiting to be written included:
     * other frames' first, then [keep]'s own.
     */
    private fun trimFrameQueues(keep: FrameInbox) {
        var total = frameInboxes.values.sumOf { it.queuedChars } + encodingChars.get()
        for (inbox in frameInboxes.values.filter { it !== keep } + keep) {
            if (total <= MAX_QUEUED_CHARS_PER_TAB) return
            while (inbox.queued.isNotEmpty() && total > MAX_QUEUED_CHARS_PER_TAB) {
                val dropped = inbox.queued.removeFirst().length
                inbox.queuedChars -= dropped
                total -= dropped
            }
        }
    }

    /** Answers reach the main frame's channel through the page, a frame inside it through its listen query. */
    private fun replyTo(name: String, text: String, frame: String?) {
        val deliver = Runnable {
            if (closed) {
                return@Runnable
            } else if (frame != null && text.length > MAX_QUEUED_CHARS_PER_FRAME) {
                AppLogger.w(PAGE_TAG, "An answer for a frame was too large and was dropped (${text.length} chars)")
            } else if (frame != null) {
                val inbox = frameInbox(name, frame)
                inbox.queued.addLast(text)
                inbox.queuedChars += text.length
                while (inbox.queued.size > MAX_QUEUED_PER_FRAME || inbox.queuedChars > MAX_QUEUED_CHARS_PER_FRAME) {
                    inbox.queuedChars -= inbox.queued.removeFirst().length
                }
                trimFrameQueues(keep = inbox)
                flush(inbox)
            } else {
                browser.evaluateJavaScript(
                    "(function(){var c=window[${JSONObject.quote(name)}];if(c&&c.__deliver)c.__deliver(${JSONObject.quote(text)});})()"
                )
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) deliver.run() else main.post(deliver)
    }
}

/** JSON with every non-ASCII character written as \uXXXX (only string contents have any). */
private fun asciiJson(json: String): String {
    if (json.all { it.code < 0x80 }) return json
    val out = StringBuilder(json.length + 64)
    val hex = "0123456789abcdef"
    for (c in json) {
        val code = c.code
        if (code < 0x80) {
            out.append(c)
        } else {
            out.append("\\u").append(hex[code shr 12 and 0xf]).append(hex[code shr 8 and 0xf])
                .append(hex[code shr 4 and 0xf]).append(hex[code and 0xf])
        }
    }
    return out.toString()
}

/** An engine request as WebView's, for the userscript rules written against it. */
private class EngineWebRequest(private val request: CefriumContentRequest) : WebResourceRequest {
    private val headers: Map<String, String> = request.headers.orEmpty()

    private fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun getUrl(): Uri = Uri.parse(request.url.orEmpty())
    // The engine does not say which frame asked; the browser marks a top-level page this way.
    override fun isForMainFrame(): Boolean = header("Sec-Fetch-Dest").equals("document", ignoreCase = true)
    override fun isRedirect(): Boolean = false
    override fun hasGesture(): Boolean = false
    override fun getMethod(): String = request.method.orEmpty().ifBlank { "GET" }
    override fun getRequestHeaders(): Map<String, String> = headers
}

private fun WebResourceResponse.toEngineResponse(): CefriumContentResponse {
    val response = CefriumContentResponse(mimeType?.ifBlank { null } ?: "application/octet-stream", data ?: ByteArrayInputStream(ByteArray(0)))
    response.setStatus(statusCode.takeIf { it >= 100 } ?: 200, reasonPhrase?.ifBlank { null } ?: "OK")
    encoding?.ifBlank { null }?.let { response.setCharset(it) }
    responseHeaders?.forEach { (name, value) -> if (name != null && value != null) response.setHeader(name, value) }
    return response
}

private val CLOSED_JSON = JSONObject().put("message", "the tab is closed").toString()
private val BUSY_JSON = JSONObject().put("message", "too many DevTools calls of this tab are still waiting for an answer").toString()

/** A Runtime.evaluate result as WebView's evaluateJavascript gives it: JSON, "null", or null when it threw. */
private fun evaluateResultValue(resultJson: String?): String? {
    val reply = runCatching { JSONObject(resultJson ?: return null) }.getOrNull() ?: return null
    if (reply.has("exceptionDetails")) return null
    val result = reply.optJSONObject("result")
    if (result == null || !result.has("value")) return "null"
    return when (val value = result.get("value")) {
        is String -> JSONObject.quote(value)
        JSONObject.NULL -> "null"
        else -> value.toString()
    }
}
