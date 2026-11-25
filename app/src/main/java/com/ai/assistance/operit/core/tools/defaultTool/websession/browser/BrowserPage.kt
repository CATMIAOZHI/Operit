package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.graphics.Bitmap
import android.view.View
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The page engine behind one browser tab: the system WebView, or the Cefrium engine built into
 * the browser build. The browser window places, scales and parks [view] like any other view;
 * everything that asks the engine itself goes through this interface. Called on the main thread.
 */
internal interface BrowserPage {
    /** What the browser window shows; a WebView, or a view that draws the engine's frames. */
    val view: View
    val url: String?
    val title: String?
    /** Loading progress, 0 to 100. */
    val progress: Int
    /** View pixels per CSS pixel at the current zoom. */
    val scale: Float
    /** Whether the page can run scripts now: a WebView must be in a window, an engine tab always can. */
    val canRunScripts: Boolean
    /** The user agent sent with requests. */
    var userAgent: String

    /**
     * Makes the page's client hints name Chrome on Android, or in desktop mode Chrome on Android's
     * "Desktop site" (Linux), to match [userAgent]. The WebView's are set with its settings when
     * the tab is configured.
     */
    fun applyChromeClientHints(desktop: Boolean) {}

    fun loadUrl(url: String, headers: Map<String, String> = emptyMap())
    fun canGoBack(): Boolean
    fun canGoForward(): Boolean
    fun goBack()
    fun goForward()
    fun canGoBackOrForward(steps: Int): Boolean
    fun goBackOrForward(steps: Int)
    /** The tab's back and forward list as last known; empty before the first page. */
    fun backForwardList(): BrowserBackForwardList
    fun reload()
    fun stopLoading()

    /** Runs [script] in the main frame; [callback] gets the result as JSON text, like WebView's. */
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?)

    /** Runs [script] at the start of every document in every frame; null when it could not be added. */
    fun addDocumentStartScript(script: String): BrowserScriptHandle?

    /**
     * Gives the page's scripts `window.<name>.postMessage(text)`; each message reaches [onMessage]
     * with whether it came from the main frame and a way to answer it. False when unsupported.
     */
    fun addMessageListener(name: String, onMessage: (message: String, isMainFrame: Boolean, reply: BrowserMessageReply) -> Unit): Boolean

    /** Whether the tab's sound is muted; null when the engine cannot mute. */
    fun isAudioMuted(): Boolean?
    /** Mutes or unmutes the tab's sound; false when the engine cannot. */
    fun setAudioMuted(muted: Boolean): Boolean

    /**
     * The visible part of the page at the view's size, or null when the engine has nothing to
     * show yet. A WebView draws itself; an engine tab hands over its last frame.
     */
    fun captureVisible(): Bitmap?

    fun onPause()
    fun onResume()
    fun destroy()
}

/** A tab's history: its entries, oldest first, and which one is shown. */
internal data class BrowserBackForwardList(val entries: List<Entry>, val currentIndex: Int) {
    data class Entry(val url: String, val title: String)

    companion object {
        val EMPTY = BrowserBackForwardList(emptyList(), -1)
    }
}

/** Removes a script added with [BrowserPage.addDocumentStartScript]. */
internal fun interface BrowserScriptHandle {
    fun remove()
}

/** Answers one message a page posted, back to the script that sent it. */
internal fun interface BrowserMessageReply {
    fun postMessage(message: String)
}

/** A tab on the system WebView. */
internal class WebViewBrowserPage(val webView: WebView) : BrowserPage {
    override val view: View get() = webView
    override val url: String? get() = webView.url
    override val title: String? get() = webView.title
    override val progress: Int get() = webView.progress
    @Suppress("DEPRECATION")
    override val scale: Float get() = webView.scale
    override val canRunScripts: Boolean get() = webView.isAttachedToWindow
    override var userAgent: String
        get() = webView.settings.userAgentString.orEmpty()
        set(value) { webView.settings.userAgentString = value }

    override fun loadUrl(url: String, headers: Map<String, String>) {
        if (headers.isEmpty()) webView.loadUrl(url) else webView.loadUrl(url, headers)
    }
    override fun canGoBack() = webView.canGoBack()
    override fun canGoForward() = webView.canGoForward()
    override fun goBack() = webView.goBack()
    override fun goForward() = webView.goForward()
    override fun canGoBackOrForward(steps: Int) = webView.canGoBackOrForward(steps)
    override fun goBackOrForward(steps: Int) = webView.goBackOrForward(steps)
    override fun backForwardList(): BrowserBackForwardList {
        val list = webView.copyBackForwardList()
        return BrowserBackForwardList(
            (0 until list.size).map { index ->
                val item = list.getItemAtIndex(index)
                BrowserBackForwardList.Entry(item?.url.orEmpty(), item?.title.orEmpty())
            },
            list.currentIndex,
        )
    }
    override fun reload() = webView.reload()
    override fun stopLoading() = webView.stopLoading()

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        webView.evaluateJavascript(script, callback?.let { block -> android.webkit.ValueCallback<String?> { block(it) } })
    }

    override fun addDocumentStartScript(script: String): BrowserScriptHandle? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
        val handler = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        return BrowserScriptHandle { handler.remove() }
    }

    override fun addMessageListener(
        name: String,
        onMessage: (message: String, isMainFrame: Boolean, reply: BrowserMessageReply) -> Unit,
    ): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        WebViewCompat.addWebMessageListener(webView, name, setOf("*")) { _, message, _, isMainFrame, replyProxy ->
            onMessage(message.data.orEmpty(), isMainFrame, BrowserMessageReply { replyProxy.postMessage(it) })
        }
        return true
    }

    override fun isAudioMuted(): Boolean? =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) WebViewCompat.isAudioMuted(webView) else null

    override fun setAudioMuted(muted: Boolean): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) return false
        WebViewCompat.setAudioMuted(webView, muted)
        return true
    }

    override fun captureVisible(): Bitmap? {
        if (webView.width <= 0 || webView.height <= 0) return null
        val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
        webView.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    override fun onPause() = webView.onPause()
    override fun onResume() = webView.onResume()
    override fun destroy() = webView.destroy()
}

/** The system WebView behind [page], or null for an engine tab. */
internal val BrowserPage.webViewOrNull: WebView? get() = (this as? WebViewBrowserPage)?.webView
