package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray

/**
 * The browser engine the browser build carries. The usual build has none, and every tab runs on
 * the system WebView. Called on the main thread unless a method says otherwise.
 */
internal interface BrowserEngine {
    /** Chrome's full version of the engine, for the user agent and its client hints. */
    val chromeFullVersion: String

    /** Whether the engine has already been initialized in this process. Any thread. */
    val isStarted: Boolean get() = true

    /**
     * Warms the engine's native library into the OS page cache on a background thread at most
     * once per process, without initializing the engine or allocating its memory. Safe to call
     * from any thread.
     */
    fun warmLibrary(context: Context) {}

    /**
     * A new tab with [profileName]'s logins that has not loaded anything yet. Throws when the
     * engine cannot start; the caller then opens the tab on the WebView.
     * A [desktop] tab is drawn at 1080p (see [DESKTOP_RASTER_SCALE]) instead of the screen's density.
     */
    fun createPage(context: Context, profileName: String, desktop: Boolean): EngineBrowserPage

    /** The Cookie header for [url] in [profileName]; null on the main thread, which it would block. Any thread. */
    fun cookieFor(profileName: String, url: String): String?

    /**
     * Stores Set-Cookie lines for [url] in [profileName]; true when the engine took every one.
     * Waits for it, so not on the main thread (false there). Any other thread.
     */
    fun setCookies(profileName: String, url: String, setCookies: List<String>): Boolean

    /** Signs [profileName] out of every site: its cookies and site storage. [onDone] gets whether it worked. */
    fun clearLogins(profileName: String, onDone: (Boolean) -> Unit)

    /** Deletes [profileName]'s data now, or at the next start when the engine still holds it. */
    fun deleteProfile(profileName: String)
}

internal object BrowserEngines {
    /** The engine built into this version, or null when tabs use the system WebView. */
    val current: BrowserEngine? by lazy { runCatching { builtInBrowserEngine() }.getOrNull() }
}

/**
 * Device pixels per CSS pixel of a desktop tab on the engine: its 1280×720 page is drawn at
 * 1920×1080, about twice what a phone shows of it, instead of at the screen's density (4480×2520
 * at 3.5), whose every frame the page view copies and uploads on the main thread. Pinching past
 * about 1.8× softens the text. Never more than the screen's density.
 */
internal const val DESKTOP_RASTER_SCALE = 1.5f

/** Device pixels per CSS pixel [page] is laid out at: its own for an engine tab, the screen's for a WebView. */
internal fun layoutScaleOf(page: BrowserPage?, density: Float): Float =
    (page as? EngineBrowserPage)?.scale?.takeIf { it.isFinite() && it > 0f } ?: density

/** A tab on the built-in engine. Its session listens through [events]. */
internal interface EngineBrowserPage : BrowserPage {
    var events: BrowserPageEvents?
    val devTools: BrowserDevTools

    /** Whether requests go to [BrowserPageEvents.interceptRequest]; on only while the tab has request rules. */
    var interceptsRequests: Boolean
}

/** The engine's DevTools protocol, in process. Main thread. */
internal interface BrowserDevTools {
    /**
     * Runs [method]; [onResult] gets, on the main thread, whether it worked and its result or error
     * as JSON. The returned function drops the call if it is still waiting to be sent (the caller
     * gave up on it); a call already sent runs to its end.
     */
    fun execute(method: String, paramsJson: String, onResult: ((Boolean, String) -> Unit)?): () -> Unit

    /** Every event of the domains that were enabled, as method and JSON parameters, on the main thread. */
    var eventListener: ((String, String) -> Unit)?

    /** Asks the engine to draw again: a capture waits for the next frame. */
    fun requestFrame()
}

/**
 * The page's own long-press menu (cut, copy, paste) while it shows: the back key closes it before
 * anything else, as it would close a WebView's. Main thread.
 */
internal object BrowserPageMenus {
    var open: android.widget.PopupWindow? = null
        set(value) {
            if (field === value) return
            field = value
            onChanged?.invoke()
        }

    /** Told when a menu shows or goes; the browser window then decides again who takes back. */
    var onChanged: (() -> Unit)? = null

    /** Closes the menu if one shows; true when it did. */
    fun dismissOpen(): Boolean {
        val menu = open ?: return false
        open = null
        runCatching { menu.dismiss() }
        return true
    }
}

/**
 * What an engine tab reports to its session; the WebView's clients report the same things to the
 * same handlers. Main thread.
 */
internal interface BrowserPageEvents {
    fun onPageStarted(url: String)
    /** The address changed: a new document, or the page's own history. */
    fun onUrlChanged(url: String)
    fun onPageFinished(url: String)
    fun onTitleChanged(title: String)
    /** A main-frame navigation the page starts; true cancels it (for example to open another app). */
    fun shouldOverrideNavigation(url: String): Boolean
    fun onSslError(url: String, reason: String)
    fun onRenderProcessGone(crashed: Boolean)
    fun onConsoleMessage(level: String, message: String, sourceId: String?, lineNumber: Int?)
    /** alert, confirm or prompt; [answer] takes OK or Cancel and the prompt's text. */
    fun onJsDialog(type: String, message: String, defaultValue: String?, url: String?, answer: (Boolean, String?) -> Unit)
    /** A file input asks for files; [answer] takes the chosen ones, or null for none. */
    fun onFileChooser(acceptTypes: List<String>, multiple: Boolean, answer: (Array<Uri>?) -> Unit)
    /** [kinds] are site-permission kinds ("camera", "microphone", "location", ...); [answer] grants all of them or none. */
    fun onPermissionRequest(origin: String, kinds: Set<String>, answer: (Boolean) -> Unit)
    /** The page opened a window; false refuses it. */
    fun onCreateWindow(popup: EngineBrowserPage, url: String): Boolean
    /** A window from [onCreateWindow] closed itself. */
    fun onCloseWindow(popup: EngineBrowserPage)
    fun onDownload(url: String, mimeType: String?, suggestedName: String?, contentLength: Long)
    /** A call to one of the app's script objects (download, async result, text selection). Any thread. */
    fun onScriptBridge(bridge: String, method: String, args: JSONArray)
    /**
     * A request the page sends; a response answers it instead of the network (userscripts'
     * webRequest rules), as WebView's shouldInterceptRequest. Background thread, may block.
     */
    fun interceptRequest(request: WebResourceRequest): WebResourceResponse?
}
