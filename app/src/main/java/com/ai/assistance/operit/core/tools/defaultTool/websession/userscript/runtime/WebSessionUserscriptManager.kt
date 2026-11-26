package com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.runtime

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserMessageReply
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserProfiles
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserPage
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserScriptHandle
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserEngines
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.EngineBrowserPage
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.ParsedUserscriptMetadata
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptBootstrapPayload
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptCapabilityRegistry
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptInstallPreview
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptInstallSourceType
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptListItem
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptMatcher
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptPageMenuCommand
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptPageRuntimeState
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptPageRuntimeStatus
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptSupportState
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.install.UserscriptImportCoordinator
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.storage.UserscriptRepository
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.ui.WebSessionUserscriptUiStateStore
import com.ai.assistance.operit.util.AppLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal class WebSessionUserscriptManager(
    private val context: Context,
    private val onOpenUserscriptUi: () -> Unit,
    private val onOpenTab: (sourceSessionId: String, url: String, active: Boolean) -> String?,
    private val onActivateSession: (sessionId: String) -> Unit,
    private val onCloseSession: (sessionId: String) -> Boolean,
    private val onDownload: (sessionId: String, url: String, fileName: String?) -> Unit,
    private val onMenuCommandsChanged: (sessionId: String?) -> Unit,
    private val onToast: (message: String) -> Unit,
    /** The login set (browser profile) a tab uses, or null for a tab that is gone. */
    private val profileOf: (sessionId: String) -> String?
) {
    private data class SessionBinding(
        val sessionId: String,
        val page: BrowserPage,
        val scriptHandler: BrowserScriptHandle?,
        val menuCommands: LinkedHashMap<String, UserscriptPageMenuCommand> = linkedMapOf()
    ) {
        /**
         * By document id (each document draws its own, see UserscriptBootstrapScript), the scripts
         * the host gave that document and its origin, with what their @grant allows. Any page in the
         * tab can post to the bridge: only a document's own scripts' calls are carried out, so what
         * one document was given never serves the next. Oldest first; emptied when the main frame
         * asks for a new document's scripts; guarded by itself.
         */
        val documents = LinkedHashMap<String, DocumentGrants>()
        /** Counts the main frame's documents: an answer for an older one grants nothing. */
        var grantEpoch = 0
        /** The main frame's current document, once it asked for its scripts. */
        var mainDocument: String? = null

        /** What [document] of [origin] was given; a document of another origin gets nothing. */
        fun scripts(document: String, origin: String): Map<Long, GrantedScript> =
            synchronized(documents) { documents[document]?.takeIf { it.origin == origin }?.scripts }.orEmpty()
    }

    /** The scripts one document of [origin] was given; never changed, replaced whole. */
    private class DocumentGrants(val origin: String, val scripts: Map<Long, GrantedScript>)

    private data class SessionPageState(
        var pageUrl: String = "about:blank",
        val scriptStatuses: ConcurrentHashMap<Long, UserscriptPageRuntimeStatus> = ConcurrentHashMap()
    )

    /** A script the host gave a frame's origin, and the host messages its @grant allows. */
    private class GrantedScript(
        val hostMessages: Set<String>,
        val metadata: ParsedUserscriptMetadata?
    )

    /**
     * A request the host sends for a page: each hop, redirects included, is checked before it is
     * sent; the request's own credentials go only to [firstOrigin]; [cookies], when set, are the
     * tab's, read and stored for each hop's own address.
     */
    private class HopPolicy(
        val firstOrigin: String,
        val allows: (HttpUrl) -> Boolean,
        val cookies: UserscriptCookieStore?
    )

    /**
     * One GM_xmlhttpRequest, from the moment the page asks: an abort that comes before the request
     * is sent still stops it.
     */
    private class XhrRequest {
        @Volatile
        var canceled = false
            private set
        @Volatile
        private var call: Call? = null

        fun bind(call: Call) {
            this.call = call
            if (canceled) call.cancel()
        }

        fun cancel() {
            canceled = true
            call?.cancel()
        }
    }

    companion object {
        private const val TAG = "WebSessionUserscript"
        private const val NOTIFICATION_CHANNEL_ID = "userscript_notifications"
        private const val NOTIFICATION_ID = 50142
        /** A page's log line is cut to this many characters. */
        private const val MAX_LOG_CHARS = 4_000
        /** Documents (frames) of one tab whose grants are kept: the main frame's and the latest frames'. */
        private const val MAX_DOCUMENTS = 32
        private const val MAX_REDIRECTS = 20
        private val CREDENTIAL_HEADERS = listOf("Cookie", "Authorization", "Proxy-Authorization")

        /** scheme://host:port of [url], ports always written, by OkHttp's own reading of the address. */
        private fun originOf(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"
    }

    private val repository = UserscriptRepository.getInstance(context.applicationContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val storageNotifier = UserscriptStorageNotifier()
    private val tabStateStore = UserscriptTabStateStore()
    /** By browser and profile: a tab's cookies are those of the browser it runs on, with its logins. */
    private val cookieStores = ConcurrentHashMap<String, UserscriptCookieStore>()
    private val cookieServices = ConcurrentHashMap<String, UserscriptCookieService>()
    private val webRequestEngine = UserscriptWebRequestEngine()

    /**
     * Requests the host sends for pages (GM_xmlhttpRequest, webRequest rewrites). Redirects are
     * followed here, one hop at a time, so each is checked before anything is sent to it.
     */
    private val requestClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain -> followHops(chain) }
            .build()

    private fun followHops(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val policy = request.tag(HopPolicy::class.java) ?: return chain.proceed(request)
        var hops = 0
        while (true) {
            if (!policy.allows(request.url)) {
                throw java.io.IOException("connect_not_allowed: ${request.url.host}")
            }
            val sameOrigin = originOf(request.url) == policy.firstOrigin
            val builder = request.newBuilder()
            if (!sameOrigin) {
                CREDENTIAL_HEADERS.forEach { builder.removeHeader(it) }
            }
            if (policy.cookies != null && !(sameOrigin && request.header("Cookie") != null)) {
                policy.cookies.get(request.url.toString())?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
            }
            val response = chain.proceed(builder.build())
            policy.cookies?.let { cookies ->
                response.headers("Set-Cookie").takeIf { it.isNotEmpty() }?.let { cookies.set(request.url.toString(), it) }
            }
            val next = redirectOf(request, response) ?: return response
            response.close()
            if (++hops > MAX_REDIRECTS) {
                throw java.net.ProtocolException("Too many redirects")
            }
            request = next
        }
    }

    /** The request a redirect leads to, as browsers make it; null when [response] is no redirect. */
    private fun redirectOf(request: Request, response: Response): Request? {
        val code = response.code
        if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) return null
        val location = response.header("Location") ?: return null
        val url = request.url.resolve(location) ?: return null
        val builder = request.newBuilder().url(url)
        val toGet =
            (code == 303 && request.method != "HEAD") ||
                ((code == 301 || code == 302) && request.method == "POST")
        if (toGet) {
            builder.method("GET", null).removeHeader("Content-Type").removeHeader("Content-Length")
        }
        return builder.build()
    }

    private val sessionBindings = ConcurrentHashMap<String, SessionBinding>()
    /** Enabled scripts and when each was last updated, once the list was read; guarded by [scriptsLock]. */
    @Volatile
    private var liveScripts: Map<Long, Long>? = null
    /** Counts changes to the script list: scripts read before a change are read again. */
    @Volatile
    private var scriptsGeneration = 0
    private val scriptsLock = Any()
    private val sessionPageStates = ConcurrentHashMap<String, SessionPageState>()
    /** By "session:document:request id": a page's own requests, until they end. */
    private val xhrRequests = ConcurrentHashMap<String, XhrRequest>()
    private val openedTabOwners = ConcurrentHashMap<String, String>()
    private val audioMuteStates = ConcurrentHashMap<String, Boolean>()
    @Volatile
    private var visibleSessionId: String? = null
    /** Whether the phone's WebView can run userscripts; the built-in engine always can. */
    private val webViewSupportsUserscripts =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private val supportState =
        UserscriptSupportState(
            isSupported = BrowserEngines.current != null || webViewSupportsUserscripts,
            reason =
                when {
                    BrowserEngines.current != null -> null
                    !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ->
                        "Current WebView does not support document-start script injection"
                    !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ->
                        "Current WebView does not support native userscript messaging"
                    else -> null
                }
        )

    val uiStore = WebSessionUserscriptUiStateStore(initialSupportState = supportState)

    init {
        webRequestEngine.onRulesChanged = { sessionId -> syncRequestInterception(sessionId) }
        scope.launch {
            repository.installedScriptsFlow.collectLatest { scripts ->
                uiStore.updateScripts(scripts)
                // A script turned off, deleted or updated stops being able to act in pages already
                // open: an update may have dropped an @grant or a rule.
                val live = scripts.filter { it.enabled }.associate { it.id to it.updatedAt }
                synchronized(scriptsLock) {
                    // Taken back under the same lock grants are given under: a page asking right
                    // after the change gets the new version, which is not taken back here.
                    val previous = liveScripts
                    val kept = live.filter { (id, updatedAt) -> previous?.get(id).let { it == null || it == updatedAt } }.keys
                    liveScripts = live
                    scriptsGeneration++
                    sessionBindings.values.forEach { binding -> revokeExcept(binding, kept) }
                    webRequestEngine.retainScripts(kept)
                }
                rebuildAllSessionBaselines()
            }
        }
        scope.launch {
            repository.observeRecentLogs().collectLatest { logs ->
                uiStore.updateLogs(logs)
            }
        }
    }

    fun supportState(): UserscriptSupportState = supportState

    fun updateVisibleSession(
        sessionId: String?,
        pageUrl: String?
    ) {
        visibleSessionId = sessionId
        if (!sessionId.isNullOrBlank() && !pageUrl.isNullOrBlank()) {
            val state = sessionPageStates.getOrPut(sessionId) { SessionPageState() }
            if (state.pageUrl != pageUrl) {
                state.pageUrl = pageUrl
                if (state.scriptStatuses.isEmpty()) {
                    rebuildSessionBaseline(sessionId)
                    return
                }
                publishVisibleStatuses()
                return
            }
            if (state.scriptStatuses.isEmpty() && pageUrl != "about:blank") {
                rebuildSessionBaseline(sessionId)
                return
            }
        }
        publishVisibleStatuses()
    }

    fun onPageChanged(
        sessionId: String,
        pageUrl: String,
        forceReset: Boolean = false
    ) {
        resetPageState(sessionId, sessionPageStates.getOrPut(sessionId) { SessionPageState() }, pageUrl, forceReset)
    }

    /** As [onPageChanged], for a tab that is still open only: a closed tab's state is not brought back. */
    private fun onOpenPageChanged(sessionId: String, pageUrl: String) {
        resetPageState(sessionId, sessionPageStates[sessionId] ?: return, pageUrl, forceReset = false)
    }

    private fun resetPageState(
        sessionId: String,
        state: SessionPageState,
        pageUrl: String,
        forceReset: Boolean
    ) {
        if (!forceReset && state.pageUrl == pageUrl && state.scriptStatuses.isNotEmpty()) {
            return
        }
        state.pageUrl = pageUrl
        state.scriptStatuses.clear()
        webRequestEngine.clearSession(sessionId)
        rebuildSessionBaseline(sessionId)
    }

    fun syncUrlChange(
        sessionId: String,
        pageUrl: String
    ) {
        val state = sessionPageStates.getOrPut(sessionId) { SessionPageState() }
        state.pageUrl = pageUrl
        publishVisibleStatuses()
    }

    fun attachSession(
        sessionId: String,
        page: BrowserPage
    ) {
        // A tab the engine could not open runs on the WebView, which may not run userscripts.
        if (!supportState.isSupported || (page !is EngineBrowserPage && !webViewSupportsUserscripts)) {
            return
        }
        val existing = sessionBindings[sessionId]
        if (existing?.page === page) {
            return
        }
        sessionPageStates.putIfAbsent(sessionId, SessionPageState())
        val attachNow = {
            runCatching { existing?.scriptHandler?.remove() }
            val scriptHandler =
                runCatching {
                    page.addDocumentStartScript(UserscriptBootstrapScript.documentStartScript())
                }.getOrElse { error ->
                    AppLogger.e(TAG, "Failed to add document-start userscript runtime", error)
                    null
                }
            runCatching {
                page.addMessageListener(UserscriptBootstrapScript.BRIDGE_NAME) { message, isMainFrame, sourceOrigin, reply ->
                    handleBridgeMessage(
                        sessionId = sessionId,
                        rawMessage = message,
                        replyProxy = reply,
                        isMainFrame = isMainFrame,
                        sourceOrigin = sourceOrigin
                    )
                }
            }.onFailure { error ->
                AppLogger.e(TAG, "Failed to add userscript message listener", error)
            }
            sessionBindings[sessionId] =
                SessionBinding(
                    sessionId = sessionId,
                    page = page,
                    scriptHandler = scriptHandler
                )
            syncRequestInterception(sessionId)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attachNow()
        } else {
            mainHandler.post(attachNow)
        }
    }

    fun detachSession(sessionId: String) {
        val binding = sessionBindings.remove(sessionId) ?: return
        sessionPageStates.remove(sessionId)
        if (visibleSessionId == sessionId) {
            visibleSessionId = null
        }
        val removeNow = {
            runCatching { binding.scriptHandler?.remove() }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            removeNow()
        } else {
            mainHandler.post(removeNow)
        }
        xhrRequests.entries.removeAll { entry ->
            val remove = entry.key.startsWith("$sessionId:")
            if (remove) {
                runCatching { entry.value.cancel() }
            }
            remove
        }
        webRequestEngine.clearSession(sessionId)
        tabStateStore.clearSession(sessionId)
        audioMuteStates.remove(sessionId)
        // Cookie helpers of browsers and profiles no open tab uses any more go with the last one.
        val inUse = sessionBindings.keys.mapNotNullTo(HashSet()) { cookieKey(it) }
        cookieStores.keys.retainAll(inUse)
        cookieServices.keys.retainAll(inUse)
        openedTabOwners.remove(sessionId)?.let { ownerSessionId ->
            dispatchHostEvent(
                ownerSessionId,
                "open_tab_closed",
                JSONObject().put("sessionId", sessionId)
            )
        }
        onMenuCommandsChanged(sessionId)
        publishVisibleStatuses()
    }

    fun getMenuCommands(sessionId: String?): List<UserscriptPageMenuCommand> {
        if (sessionId.isNullOrBlank()) {
            return emptyList()
        }
        return sessionBindings[sessionId]?.menuCommands?.values?.toList().orEmpty()
    }

    fun invokeMenuCommand(
        sessionId: String?,
        commandId: String
    ) {
        if (sessionId.isNullOrBlank() || commandId.isBlank()) {
            return
        }
        val binding = sessionBindings[sessionId] ?: return
        mainHandler.post {
            val escaped = JSONObject.quote(commandId)
            binding.page.evaluateJavascript(
                """
                (function() {
                    if (window.__operitUserscriptRuntime &&
                        typeof window.__operitUserscriptRuntime.invokeMenuCommand === "function") {
                        window.__operitUserscriptRuntime.invokeMenuCommand($escaped);
                    }
                })();
                """.trimIndent(),
                null
            )
        }
    }

    fun beginUrlInstall(rawUrl: String, sourceType: UserscriptInstallSourceType = UserscriptInstallSourceType.REMOTE_URL) {
        if (!supportState.isSupported) {
            mainHandler.post(onOpenUserscriptUi)
            return
        }
        val normalizedUrl = rawUrl.trim()
        val scheme =
            runCatching { android.net.Uri.parse(normalizedUrl).scheme?.lowercase() }.getOrNull()
        if (normalizedUrl.isBlank() || (scheme != "http" && scheme != "https")) {
            onToast(context.getString(R.string.web_session_userscript_invalid_url))
            return
        }
        scope.launch {
            runCatching {
                repository.fetchRemotePreview(normalizedUrl, sourceType)
            }.onSuccess { preview ->
                uiStore.setPendingInstall(preview)
                mainHandler.post(onOpenUserscriptUi)
            }.onFailure { error ->
                repository.log(null, "error", normalizedUrl, error.message ?: "userscript install preview failed")
                mainHandler.post {
                    onToast(error.message ?: context.getString(R.string.web_session_userscript_install_failed))
                }
            }
        }
    }

    fun beginLocalImport() {
        if (!supportState.isSupported) {
            mainHandler.post(onOpenUserscriptUi)
            return
        }
        scope.launch {
            val result = UserscriptImportCoordinator.requestImport(context.applicationContext)
            if (result == null) {
                return@launch
            }
            runCatching {
                repository.prepareInstallPreview(
                    rawSource = result.rawSource,
                    sourceType = UserscriptInstallSourceType.LOCAL_FILE,
                    sourceUrl = result.sourceUri,
                    sourceDisplay = result.displayName
                )
            }.onSuccess { preview ->
                uiStore.setPendingInstall(preview)
                mainHandler.post(onOpenUserscriptUi)
            }.onFailure { error ->
                repository.log(null, "error", result.sourceUri, error.message ?: "userscript local import failed")
                mainHandler.post {
                    onToast(error.message ?: context.getString(R.string.web_session_userscript_install_failed))
                }
            }
        }
    }

    fun confirmPendingInstall() {
        if (!supportState.isSupported) {
            mainHandler.post(onOpenUserscriptUi)
            return
        }
        val preview = uiStore.state.value.pendingInstall ?: return
        scope.launch {
            runCatching {
                repository.install(preview)
            }.onSuccess { installed ->
                uiStore.setPendingInstall(null)
                mainHandler.post {
                    onToast(
                        context.getString(
                            R.string.web_session_userscript_installed,
                            installed.name
                        )
                    )
                    onOpenUserscriptUi()
                }
            }.onFailure { error ->
                repository.log(preview.existingScriptId, "error", preview.sourceUrl, error.message ?: "userscript install failed")
                mainHandler.post {
                    onToast(error.message ?: context.getString(R.string.web_session_userscript_install_failed))
                }
            }
        }
    }

    fun cancelPendingInstall() {
        uiStore.setPendingInstall(null)
    }

    fun setScriptEnabled(
        scriptId: Long,
        enabled: Boolean
    ) {
        scope.launch {
            repository.setEnabled(scriptId, enabled)
        }
    }

    fun deleteScript(scriptId: Long) {
        scope.launch {
            repository.deleteUserscript(scriptId)
            mainHandler.post {
                onToast(context.getString(R.string.web_session_userscript_deleted))
            }
        }
    }

    fun checkForUpdate(scriptId: Long) {
        scope.launch {
            val preview = repository.checkForUpdate(scriptId)
            if (preview != null) {
                uiStore.setPendingInstall(preview)
                mainHandler.post(onOpenUserscriptUi)
            } else {
                mainHandler.post {
                    onToast(context.getString(R.string.web_session_userscript_no_update))
                }
            }
        }
    }

    fun currentInstalledScript(scriptId: Long, callback: (UserscriptListItem?) -> Unit) {
        scope.launch {
            val item = repository.getInstalledScript(scriptId)
            mainHandler.post { callback(item) }
        }
    }

    /**
     * A built-in engine tab hands its requests to [interceptWebRequest] only while the tab has
     * webRequest rules: asking about every request costs the engine a thread hop each.
     */
    private fun syncRequestInterception(sessionId: String) {
        val apply = {
            val page = sessionBindings[sessionId]?.page as? EngineBrowserPage
            page?.interceptsRequests = webRequestEngine.hasRules(sessionId)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else mainHandler.post { apply() }
    }

    fun interceptWebRequest(
        sessionId: String,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url?.toString().orEmpty()
        // Most tabs have no rules: their requests are not looked at further.
        if (url.isBlank() || !webRequestEngine.hasRules(sessionId)) {
            return null
        }
        val resolution =
            webRequestEngine.resolve(
                sessionId = sessionId,
                url = url,
                requestType = resolveWebRequestType(request)
            )
        if (resolution.matches.isEmpty()) {
            return null
        }
        tellWebRequestMatches(sessionId, resolution.matches, url, resolveWebRequestType(request))
        val action = resolution.mergedAction
        if (!action.requiresInterception()) {
            return null
        }
        if (action.cancel) {
            return WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        val targetUrl = action.redirectUrl ?: url
        // Sent elsewhere, by the rule or by redirects, the page's request keeps no credentials
        // meant for its own origin. Only rules scripts declared act here (see resolve).
        val policy =
            HopPolicy(
                firstOrigin = url.toHttpUrlOrNull()?.let { originOf(it) }.orEmpty(),
                allows = { true },
                cookies = null
            )
        return runCatching {
            val requestBuilder = Request.Builder().url(targetUrl).tag(HopPolicy::class.java, policy)
            request.requestHeaders.orEmpty().forEach { (key, value) ->
                requestBuilder.header(key, value)
            }
            action.requestHeaders.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }
            val response = requestClient.newCall(requestBuilder.get().build()).execute()
            val bodyBytes =
                action.responseBody?.toByteArray(Charsets.UTF_8)
                    ?: response.body?.bytes()
                    ?: ByteArray(0)
            val responseHeaders =
                response.headers.toMultimap()
                    .mapValues { (_, values) -> values.joinToString(", ") }
                    .toMutableMap()
                    .apply { putAll(action.responseHeaders) }
            val mimeType =
                response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.ifBlank { "application/octet-stream" }
                    ?: "application/octet-stream"
            val encoding =
                response.header("Content-Type")
                    ?.substringAfter("charset=", "")
                    ?.substringBefore(';')
                    ?.ifBlank { "utf-8" }
                    ?: "utf-8"
            WebResourceResponse(
                mimeType,
                encoding,
                response.code,
                response.message,
                responseHeaders,
                ByteArrayInputStream(bodyBytes)
            ).also {
                response.close()
            }
        }.getOrElse { error ->
            AppLogger.w(TAG, "userscript webRequest intercept failed: ${error.message}")
            null
        }
    }

    private fun handleBridgeMessage(
        sessionId: String,
        rawMessage: String,
        replyProxy: BrowserMessageReply,
        isMainFrame: Boolean,
        sourceOrigin: String?
    ) {
        val message = runCatching { JSONObject(rawMessage) }.getOrNull() ?: return
        val type = message.optString("type", "")
        val requestId = message.optString("requestId", "")
        val payload = message.optJSONObject("payload") ?: JSONObject()
        val binding = sessionBindings[sessionId] ?: return
        // Any page in the tab can post here, so what is done follows the origin the engine
        // reported and the scripts given to the document that sends it, never what it claims.
        val origin = webOrigin(sourceOrigin)
        val document = message.optString("doc", "").takeIf { it.length in 16..64 }
        val granted = if (origin == null || document == null) emptyMap() else binding.scripts(document, origin)
        if (origin == null || document == null || !mayHandle(granted, origin, type, payload, isMainFrame)) {
            if (requestId.isNotBlank()) {
                postRpcError(replyProxy, requestId, "not_allowed")
            }
            // A refused request still ends, so the script's callbacks do not wait forever.
            if (type == "gm_xmlhttp_request") {
                postXhrRefused(replyProxy, payload.optString("requestId", "").trim(), payload.optString("url", ""))
            }
            return
        }
        when (type) {
            "bootstrap_request" -> {
                val href = payload.optString("href", "")
                // A new main document: what the last one and its frames were given goes with it.
                val epoch =
                    synchronized(binding.documents) {
                        if (isMainFrame) {
                            binding.documents.clear()
                            binding.mainDocument = document
                            binding.grantEpoch++
                        }
                        binding.grantEpoch
                    }
                scope.launch {
                    runCatching {
                        val noScripts = JSONObject().put("payloadJson", json.encodeToString(UserscriptBootstrapPayload()))
                        // A page that names another site's address gets no scripts. A frame of
                        // about:blank or srcdoc cannot prove its address, so it gets none either.
                        if (webOrigin(href) != origin) {
                            postRpcSuccess(replyProxy = replyProxy, requestId = requestId, payload = noScripts)
                            return@runCatching
                        }
                        val stillShown = { sessionBindings[sessionId] === binding && binding.grantEpoch == epoch }
                        if (isMainFrame && stillShown()) {
                            onOpenPageChanged(sessionId, href)
                        }
                        val bootstrapPayload = publishScripts(binding, document, origin, href, isMainFrame, stillShown)
                        // The tab closed or showed another document meanwhile: its scripts would not run there.
                        if (bootstrapPayload == null) {
                            postRpcSuccess(replyProxy = replyProxy, requestId = requestId, payload = noScripts)
                            return@runCatching
                        }
                        // A closed tab's page state is not brought back.
                        val state = if (isMainFrame && sessionBindings[sessionId] === binding) sessionPageStates[sessionId] else null
                        if (state != null) {
                            bootstrapPayload.scripts.forEach { script ->
                                state.scriptStatuses[script.scriptId] =
                                    UserscriptPageRuntimeStatus(
                                        state = UserscriptPageRuntimeState.QUEUED,
                                        detail = script.runAt
                                    )
                            }
                            publishVisibleStatuses()
                        }
                        postRpcSuccess(
                            replyProxy = replyProxy,
                            requestId = requestId,
                            payload =
                                JSONObject().put(
                                    "payloadJson",
                                    json.encodeToString(bootstrapPayload)
                                )
                        )
                    }.onFailure { error ->
                        postRpcError(replyProxy, requestId, error.message ?: "bootstrap_request_failed")
                    }
                }
            }

            "script_status" -> {
                if (!isMainFrame) {
                    return
                }
                val scriptId = payload.optLong("scriptId")
                if (scriptId > 0L) {
                    upsertRuntimeStatus(
                        sessionId = sessionId,
                        scriptId = scriptId,
                        rawState = payload.optString("state", ""),
                        detail = payload.optString("message", "").ifBlank { null }
                    )
                }
            }

            "runtime_log" -> {
                scope.launch {
                    repository.log(
                        userscriptId = payload.optLong("scriptId").takeIf { it > 0L },
                        level = payload.optString("level", "info"),
                        pageUrl = payload.optString("pageUrl", "").ifBlank { null }?.take(MAX_LOG_CHARS),
                        message = payload.optString("message", "userscript runtime message").take(MAX_LOG_CHARS)
                    )
                }
            }

            "menu_reset" -> {
                if (!isMainFrame) {
                    return
                }
                sessionBindings[sessionId]?.menuCommands?.clear()
                onMenuCommandsChanged(sessionId)
            }

            "register_menu_command" -> {
                if (!isMainFrame) {
                    return
                }
                val commandId = payload.optString("commandId", "").trim()
                val title = payload.optString("title", "").trim()
                val userscriptId = payload.optLong("scriptId")
                // Commands are named "<script id>:<the script's own id>" (see UserscriptBootstrapScript).
                if (commandId.startsWith("$userscriptId:") && title.isNotBlank()) {
                    // Another script's command keeps its place.
                    val existing = binding.menuCommands[commandId]
                    if (existing != null && existing.userscriptId != userscriptId) {
                        return
                    }
                    binding.menuCommands[commandId] =
                        UserscriptPageMenuCommand(
                            commandId = commandId,
                            title = title,
                            userscriptId = userscriptId
                        )
                    onMenuCommandsChanged(sessionId)
                }
            }

            "unregister_menu_command" -> {
                if (!isMainFrame) {
                    return
                }
                val commandId = payload.optString("commandId", "").trim()
                // Only the script that added a command takes it away.
                val owner = binding.menuCommands[commandId]?.userscriptId
                if (owner == null || owner != payload.optLong("scriptId")) {
                    return
                }
                binding.menuCommands.remove(commandId)
                onMenuCommandsChanged(sessionId)
            }

            "storage_set" -> {
                val scriptId = payload.optLong("scriptId")
                val key = payload.optString("key", "")
                val valueJson = payload.optString("valueJson", "null")
                if (scriptId > 0L && key.isNotBlank()) {
                    scope.launch {
                        persistValueAndBroadcast(
                            sourceSessionId = sessionId,
                            scriptId = scriptId,
                            key = key,
                            valueJson = valueJson
                        )
                    }
                }
            }

            "storage_set_many" -> {
                val scriptId = payload.optLong("scriptId")
                val values = payload.optJSONObject("values") ?: JSONObject()
                if (scriptId > 0L) {
                    scope.launch {
                        values.keys().forEach { key ->
                            persistValueAndBroadcast(
                                sourceSessionId = sessionId,
                                scriptId = scriptId,
                                key = key,
                                valueJson = values.optString(key, "null")
                            )
                        }
                    }
                }
            }

            "storage_delete" -> {
                val scriptId = payload.optLong("scriptId")
                val key = payload.optString("key", "")
                if (scriptId > 0L && key.isNotBlank()) {
                    scope.launch {
                        deleteValueAndBroadcast(
                            sourceSessionId = sessionId,
                            scriptId = scriptId,
                            key = key
                        )
                    }
                }
            }

            "storage_delete_many" -> {
                val scriptId = payload.optLong("scriptId")
                val keys = payload.optJSONArray("keys") ?: org.json.JSONArray()
                if (scriptId > 0L) {
                    scope.launch {
                        for (index in 0 until keys.length()) {
                            val key = keys.optString(index).trim()
                            if (key.isNotBlank()) {
                                deleteValueAndBroadcast(
                                    sourceSessionId = sessionId,
                                    scriptId = scriptId,
                                    key = key
                                )
                            }
                        }
                    }
                }
            }

            "url_change" -> {
                if (!isMainFrame) {
                    return
                }
                val href = payload.optString("href", "").trim()
                if (href.isNotBlank()) {
                    syncUrlChange(sessionId, href)
                }
            }

            "gm_open_in_tab" -> {
                val rawUrl = payload.optString("url", "").trim()
                if (rawUrl.isBlank()) {
                    if (requestId.isNotBlank()) {
                        postRpcError(replyProxy, requestId, "open_tab_url_is_empty")
                    }
                    return
                }
                // Web pages only: the browser could also open the phone's files.
                val url = webAddress(rawUrl, origin)
                if (url == null) {
                    if (requestId.isNotBlank()) {
                        postRpcError(replyProxy, requestId, "open_tab_url_not_allowed")
                    }
                    return
                }
                mainHandler.post {
                    val openedSessionId = onOpenTab(sessionId, url, payload.optBoolean("active", true))
                    if (!openedSessionId.isNullOrBlank()) {
                        openedTabOwners[openedSessionId] = sessionId
                        if (requestId.isNotBlank()) {
                            postRpcSuccess(
                                replyProxy,
                                requestId,
                                JSONObject().put("sessionId", openedSessionId)
                            )
                        }
                    } else if (requestId.isNotBlank()) {
                        postRpcError(replyProxy, requestId, "open_tab_failed")
                    }
                }
            }

            "gm_focus_tab" -> {
                val targetSessionId = payload.optString("sessionId", sessionId).trim()
                mainHandler.post {
                    if (targetSessionId.isBlank() || !sessionBindings.containsKey(targetSessionId) ||
                        !isOwnTab(sessionId, targetSessionId)
                    ) {
                        if (requestId.isNotBlank()) {
                            postRpcError(replyProxy, requestId, "focus_tab_failed")
                        }
                        return@post
                    }
                    onActivateSession(targetSessionId)
                    if (requestId.isNotBlank()) {
                        postRpcSuccess(replyProxy, requestId, JSONObject().put("sessionId", targetSessionId))
                    }
                }
            }

            "gm_close_tab" -> {
                val targetSessionId = payload.optString("sessionId", sessionId).trim()
                mainHandler.post {
                    val closed =
                        if (targetSessionId.isNotBlank() && isOwnTab(sessionId, targetSessionId)) {
                            onCloseSession(targetSessionId)
                        } else {
                            false
                        }
                    if (requestId.isNotBlank()) {
                        if (closed) {
                            postRpcSuccess(replyProxy, requestId, JSONObject().put("sessionId", targetSessionId))
                        } else {
                            postRpcError(replyProxy, requestId, "close_tab_failed")
                        }
                    }
                }
            }

            "gm_get_tab" -> handleGetTab(sessionId, payload, replyProxy, requestId)
            "gm_save_tab" -> handleSaveTab(sessionId, payload, replyProxy, requestId)
            "gm_get_tabs" -> handleGetTabs(payload, replyProxy, requestId)
            "gm_cookie" ->
                handleCookie(
                    sessionId,
                    payload,
                    replyProxy,
                    requestId,
                    origin,
                    granted[payload.optLong("scriptId")]?.metadata
                )
            "gm_audio" -> handleAudio(sessionId, payload, replyProxy, requestId)
            "gm_web_request_register" ->
                handleRegisterWebRequest(binding, document, origin, isMainFrame, granted[payload.optLong("scriptId")], payload, replyProxy, requestId)
            "gm_web_request_unregister" -> handleUnregisterWebRequest(sessionId, document, granted, payload, replyProxy, requestId)
            "gm_set_clipboard" -> handleSetClipboard(payload, replyProxy, requestId)
            "gm_notification" -> handleNotification(payload, replyProxy, requestId)
            "gm_download" -> handleDownload(sessionId, payload, replyProxy, requestId, origin)
            "gm_xmlhttp_request" ->
                handleXmlHttpRequest(binding, document, granted[payload.optLong("scriptId")], payload, replyProxy, requestId, origin)
            "gm_abort_request" -> {
                val gmRequestId = payload.optString("requestId", "").trim()
                if (gmRequestId.isNotBlank()) {
                    // Only this document's own requests, known from the moment they were asked
                    // for; an unknown id leaves nothing behind.
                    xhrRequests["$sessionId:$document:$gmRequestId"]?.cancel()
                }
            }
        }
    }

    /**
     * The scripts for [document] of [origin] at [href], given to it before the reply so their first
     * calls find them. Read again when the script list changed meanwhile, so a script turned off or
     * updated is never given as it was; null when [stillShown] says the document is gone.
     */
    private suspend fun publishScripts(
        binding: SessionBinding,
        document: String,
        origin: String,
        href: String,
        isMainFrame: Boolean,
        stillShown: () -> Boolean
    ): UserscriptBootstrapPayload? {
        repeat(3) {
            val generation = scriptsGeneration
            val built = repository.buildBootstrapPayload(binding.sessionId, href, isMainFrame)
            val given =
                built.scripts.associate { script ->
                    script.scriptId to
                        GrantedScript(
                            hostMessages = UserscriptCapabilityRegistry.hostMessagesFor(script.grants),
                            metadata = runCatching {
                                json.decodeFromString<ParsedUserscriptMetadata>(script.metadataJson)
                            }.getOrNull()
                        )
                }
            synchronized(scriptsLock) {
                if (generation == scriptsGeneration) {
                    val live = liveScripts
                    val scripts = if (live == null) built.scripts else built.scripts.filter { it.scriptId in live }
                    val ids = scripts.mapTo(HashSet()) { it.scriptId }
                    synchronized(binding.documents) {
                        if (!stillShown()) return null
                        if (scripts.isNotEmpty()) {
                            binding.documents.remove(document)
                            // Frames that came and went: the oldest go, never the main frame's.
                            while (binding.documents.size >= MAX_DOCUMENTS) {
                                val oldest = binding.documents.keys.firstOrNull { it != binding.mainDocument } ?: break
                                binding.documents.remove(oldest)
                                webRequestEngine.clearDocument(binding.sessionId, oldest)
                            }
                            binding.documents[document] =
                                DocumentGrants(origin, given.filterKeys { it in ids })
                        }
                    }
                    return built.copy(scripts = scripts)
                }
            }
        }
        return null
    }

    /** Takes back what [binding]'s documents were given of scripts not in [kept]. */
    private fun revokeExcept(binding: SessionBinding, kept: Set<Long>) {
        synchronized(binding.documents) {
            binding.documents.entries.forEach { entry ->
                val grants = entry.value
                if (grants.scripts.keys.any { it !in kept }) {
                    entry.setValue(DocumentGrants(grants.origin, grants.scripts.filterKeys { it in kept }))
                }
            }
        }
    }

    /**
     * Whether a message from a document of [origin], whose scripts are [granted], is carried out.
     * Asking for scripts, the page's own address changes and plain log lines are open to every
     * page; anything else needs a script the host gave that very document whose @grant covers it.
     *
     * Scripts run in the page's own JavaScript world, so the page they were given to can also make
     * the calls their @grant allows: these grants keep every other page out, which is the line the
     * host can hold. Within one origin the address path is not a line: same-origin pages reach each
     * other's documents anyway.
     */
    private fun mayHandle(
        granted: Map<Long, GrantedScript>,
        origin: String,
        type: String,
        payload: JSONObject,
        isMainFrame: Boolean
    ): Boolean {
        val scriptId = payload.optLong("scriptId")
        val allows = { script: GrantedScript -> type in script.hostMessages }
        return when (type) {
            // Answered with the scripts for the frame's own origin only; checked where it is handled.
            "bootstrap_request" -> true
            "menu_reset" -> isMainFrame
            "url_change" -> isMainFrame && webOrigin(payload.optString("href", "")) == origin
            "script_status" -> scriptId in granted
            // A log line without a script (the runtime failing to start) is only a line in the log.
            "runtime_log" -> scriptId <= 0L || scriptId in granted
            // The rules a script declared need no @grant; what it adds while running does, and
            // acts only on addresses the page could request itself (see handleRegisterWebRequest).
            "gm_web_request_register" ->
                granted[scriptId]?.let { script ->
                    allows(script) ||
                        (payload.optString("source") == "metadata" && script.metadata?.webRequestRules?.isNotEmpty() == true)
                } == true
            // Checked against the registration's owner where it is handled.
            "gm_web_request_unregister" -> granted.isNotEmpty()
            // These name a tab or a request, not a script.
            "gm_focus_tab", "gm_close_tab", "gm_abort_request" -> granted.values.any(allows)
            else -> granted[scriptId]?.let(allows) == true
        }
    }

    /** A script may focus or close only its own tab and the tabs it opened. */
    private fun isOwnTab(sessionId: String, targetSessionId: String): Boolean =
        targetSessionId == sessionId || openedTabOwners[targetSessionId] == sessionId

    /**
     * scheme://host[:port] of an http(s) address, "file://" for the phone's own pages (one origin,
     * as the browser treats them), or null for anything else (opaque origins too).
     */
    private fun webOrigin(raw: String?): String? {
        val uri = runCatching { Uri.parse(raw?.trim().orEmpty()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme == "file") return "file://"
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        val defaultPort = if (scheme == "https") 443 else 80
        return if (uri.port == -1 || uri.port == defaultPort) "$scheme://$host" else "$scheme://$host:${uri.port}"
    }

    /** [url] as an http(s) address, a relative one taken from [origin]; null for anything else. */
    private fun webAddress(url: String, origin: String): String? {
        val isWeb = { address: String ->
            runCatching { Uri.parse(address).scheme?.lowercase(Locale.ROOT) }.getOrNull().let { it == "http" || it == "https" }
        }
        if (isWeb(url)) return url
        if (Uri.parse(url).scheme != null || !origin.startsWith("http")) return null
        val resolved = runCatching { java.net.URI("$origin/").resolve(url).toString() }.getOrNull() ?: return null
        return resolved.takeIf(isWeb)
    }

    private fun handleSetClipboard(
        payload: JSONObject,
        replyProxy: BrowserMessageReply,
        requestId: String
    ) {
        val text = payload.optString("text", "")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            if (requestId.isNotBlank()) {
                postRpcError(replyProxy, requestId, "clipboard_service_unavailable")
            }
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("userscript", text))
        if (requestId.isNotBlank()) {
            postRpcSuccess(replyProxy, requestId, JSONObject().put("text", text))
        }
    }

    private fun handleNotification(
        payload: JSONObject,
        replyProxy: BrowserMessageReply,
        requestId: String
    ) {
        val title = payload.optString("title", "").ifBlank { context.getString(R.string.web_session_userscript_notification_title) }
        val text = payload.optString("text", "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                onToast(context.getString(R.string.web_session_userscript_notification_denied))
                if (requestId.isNotBlank()) {
                    postRpcError(replyProxy, requestId, "notification_permission_denied")
                }
                return
            }
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager == null) {
            if (requestId.isNotBlank()) {
                postRpcError(replyProxy, requestId, "notification_manager_unavailable")
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.web_session_userscript_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val notification =
            NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(com.ai.assistance.operit.core.application.AppLauncherIdentity.notificationIcon(context))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
        manager.notify(NOTIFICATION_ID + payload.optLong("scriptId").toInt(), notification)
        if (requestId.isNotBlank()) {
            postRpcSuccess(
                replyProxy,
                requestId,
                JSONObject()
                    .put("shown", true)
                    .put("title", title)
                    .put("text", text)
            )
        }
    }

    private fun handleDownload(
        sessionId: String,
        payload: JSONObject,
        replyProxy: BrowserMessageReply,
        requestId: String,
        origin: String
    ) {
        val rawUrl = payload.optString("url", "").trim()
        if (rawUrl.isBlank()) {
            onToast(context.getString(R.string.web_session_userscript_download_failed))
            if (requestId.isNotBlank()) {
                postRpcError(replyProxy, requestId, "download_url_is_empty")
            }
            return
        }
        // Downloads come from the web, never from the phone's own files.
        val url = webAddress(rawUrl, origin)
        if (url == null) {
            onToast(context.getString(R.string.web_session_userscript_download_failed))
            if (requestId.isNotBlank()) {
                postRpcError(replyProxy, requestId, "download_url_not_allowed")
            }
            return
        }
        mainHandler.post {
            onDownload(sessionId, url, payload.optString("fileName", "").ifBlank { null })
            if (requestId.isNotBlank()) {
                postRpcSuccess(
                    replyProxy,
                    requestId,
                    JSONObject()
                        .put("started", true)
                        .put("url", url)
                        .put("fileName", payload.optString("fileName", ""))
                )
            }
        }
    }

    private suspend fun persistValueAndBroadcast(
        sourceSessionId: String,
        scriptId: Long,
        key: String,
        valueJson: String
    ) {
        val oldValueJson = repository.readValueJson(scriptId, key)
        repository.persistValue(scriptId, key, valueJson)
        broadcastStorageChange(
            sourceSessionId = sourceSessionId,
            change =
                UserscriptStorageChange(
                    scriptId = scriptId,
                    key = key,
                    oldValueJson = oldValueJson,
                    newValueJson = valueJson,
                    remote = true
                )
        )
    }

    private suspend fun deleteValueAndBroadcast(
        sourceSessionId: String,
        scriptId: Long,
        key: String
    ) {
        val oldValueJson = repository.readValueJson(scriptId, key)
        repository.deleteValue(scriptId, key)
        broadcastStorageChange(
            sourceSessionId = sourceSessionId,
            change =
                UserscriptStorageChange(
                    scriptId = scriptId,
                    key = key,
                    oldValueJson = oldValueJson,
                    newValueJson = null,
                    remote = true
                )
        )
    }

    private fun broadcastStorageChange(
        sourceSessionId: String,
        change: UserscriptStorageChange
    ) {
        val payload = storageNotifier.toPayload(change)
        // Only to tabs whose main frame's document runs the script: the values are the script's,
        // and the event reaches that document only.
        sessionBindings.forEach { (targetSessionId, binding) ->
            if (targetSessionId == sourceSessionId) return@forEach
            val (mainDocument, mainOrigin) =
                synchronized(binding.documents) {
                    val document = binding.mainDocument ?: return@forEach
                    val grants = binding.documents[document]?.takeIf { change.scriptId in it.scripts } ?: return@forEach
                    document to grants.origin
                }
            dispatchHostEvent(targetSessionId, "storage_changed", payload, requireOrigin = mainOrigin, requireDocument = mainDocument)
        }
    }

    private fun handleGetTab(
        sessionId: String,
        payload: JSONObject,
        replyProxy: BrowserMessageReply,
        requestId: String
    ) {
        val scriptId = payload.optLong("scriptId")
        if (scriptId <= 0L) {
            postRpcError(replyProxy, requestId, "invalid_script_id")
            return
        }
        postRpcSuccess(
            replyProxy,
            requestId,
            JSONObject().put("tabJson", tabStateStore.getTab(scriptId, sessionId))
        )
    }

    private fun handleSaveTab(
        sessionId: String,
        payload: JSONObject,
        replyProxy: BrowserMessageReply,
        requestId: String
    ) {
        val scriptId = payload.optLong("scriptId")
        if (scriptId <= 0L) {
            postRpcError(replyProxy, requestId, "invalid_script_id")
            return
        }
        val tabJson = payload.optString("tabJson", "{}")
        tabStateStore.saveTab(scriptId, sessionId, tabJson)
        postRpcSuccess(replyProxy, requestId, JSONObject().put("tabJson", tabJson))
    }

    private fun handleGetTabs(
        payload: JSONObject,
        replyProxy: BrowserMessageReply,
        requestId: String
    ) {
        val scriptId = payload.optLong("scriptId")
        if (scriptId <= 0L) {
            postRpcError(replyProxy, requestId, "invalid_script_id")
            return
        }
        val jsonPayload =
            JSONObject()
        tabStateStore.getTabs(scriptId).forEach { (sessionId, tabJson) ->
            jsonPayload.put(sessionId, tabJson)
        }
        postRpcSuccess(replyProxy, requestId, JSONObject().put("tabsJson", jsonPayload.toString()))
    }

    private fun handleCookie(
        sessionId: String,
        payload: JSONObject,
        replyProxy: BrowserMessageReply,
        requestId: String,
        origin: String,
        metadata: ParsedUserscriptMetadata?
    ) {
        val details = payload.optJSONObject("details") ?: JSONObject()
        // The page's address only when it is the frame's own; the origin otherwise.
        val pageUrl = payload.optString("pageUrl", "").takeIf { webOrigin(it) == origin } ?: origin
        val targetUrl = details.optString("url").ifBlank { pageUrl }
        if (!mayUseCookies(origin, targetUrl, metadata)) {
            postRpcError(replyProxy, requestId, "cookie_url_not_allowed")
            return
        }
        // A cookie's Domain must cover the address it is set for: no writing another site's.
        val targetHost = Uri.parse(targetUrl).host?.lowercase(Locale.ROOT).orEmpty()
        val domain = details.optString("domain").trim().removePrefix(".").lowercase(Locale.ROOT)
        if (domain.isNotEmpty() && targetHost != domain && !targetHost.endsWith(".$domain")) {
            postRpcError(replyProxy, requestId, "cookie_domain_not_allowed")
            return
        }
        val cookieService = tabCookieService(sessionId)
        if (cookieService == null) {
            postRpcError(replyProxy, requestId, "session_not_found")
            return
        }
        // The engine's cookies are read off the main thread.
        scope.launch {
            runCatching {
                when (payload.optString("action", "").trim()) {
                    "list" -> {
                        val cookiesJson = org.json.JSONArray()
                        cookieService.list(details, targetUrl).forEach { cookie ->
                            cookiesJson.put(cookie.toJson())
                        }
                        postRpcSuccess(replyProxy, requestId, JSONObject().put("cookiesJson", cookiesJson.toString()))
                    }
                    "set" -> {
                        val cookie = cookieService.set(details, targetUrl)
                        postRpcSuccess(replyProxy, requestId, JSONObject().put("cookieJson", cookie.toJson().toString()))
                    }
                    "delete" -> {
                        cookieService.delete(details, targetUrl)
                        postRpcSuccess(replyProxy, requestId, JSONObject().put("deleted", true))
                    }
                    else -> postRpcError(replyProxy, requestId, "unsupported_cookie_action")
                }
            }.onFailure { error ->
                postRpcError(replyProxy, requestId, error.message ?: "gm_cookie_failed")
            }
        }
    }

    /**
     * Whether a script given [origin] may use the cookies of [targetUrl]: its own site's, never a
     * secure site's from a plain-http page, and another site's only when its @connect names it.
     */
    private fun mayUseCookies(origin: String, targetUrl: String, metadata: ParsedUserscriptMetadata?): Boolean {
        val target = Uri.parse(targetUrl)
        val targetScheme = target.scheme?.lowercase(Locale.ROOT)
        val targetHost = target.host?.lowercase(Locale.ROOT)
        if ((targetScheme != "http" && targetScheme != "https") || targetHost.isNullOrBlank()) return false
        val page = Uri.parse(origin)
        val pageScheme = page.scheme?.lowercase(Locale.ROOT)
        if (pageScheme == "http" && targetScheme == "https") {
            return metadata != null && metadata.connects.isNotEmpty() && UserscriptMatcher.isConnectAllowed(metadata, origin, targetUrl)
        }
        if (targetHost == page.host?.lowercase(Locale.ROOT)) return true
        return metadata != null && metadata.connects.isNotEmpty() && UserscriptMatcher.isConnectAllowed(metadata, origin, targetUrl)
    }

    /** The cookies of the browser and profile [sessionId] runs on; null for a tab that is gone. */
    /** Which browser and profile [sessionId]'s cookies live in; null for a closed tab. */
    private fun cookieKey(sessionId: String): String? {
        val binding = sessionBindings[sessionId] ?: return null
        val profile = profileOf(sessionId) ?: return null
        return "${binding.page is EngineBrowserPage}\n$profile"
    }

    private fun tabCookieStore(sessionId: String): UserscriptCookieStore? {
        val binding = sessionBindings[sessionId] ?: return null
        val profile = profileOf(sessionId) ?: return null
        val onEngine = binding.page is EngineBrowserPage
        return cookieStores.getOrPut("$onEngine\n$profile") {
            object : UserscriptCookieStore {
                override fun get(url: String): String? = BrowserProfiles.cookieFor(profile, url, onEngine)

                override fun set(url: String, setCookies: List<String>): Boolean =
                    BrowserProfiles.setCookies(profile, url, setCookies, onEngine)
            }
        }
    }

    private fun tabCookieService(sessionId: String): UserscriptCookieService? {
        val store = tabCookieStore(sessionId) ?: return null
        val key = cookieKey(sessionId) ?: return null
        return cookieServices.getOrPut(key) { UserscriptCookieService(store) }
    }

    private fun handleAudio(
        sessionId: String,
        payload: JSONObject,
        replyProxy: BrowserMessageReply,
        requestId: String
    ) {
        val binding = sessionBindings[sessionId]
        if (binding == null) {
            postRpcError(replyProxy, requestId, "session_not_found")
            return
        }
        if (binding.page.isAudioMuted() == null) {
            postRpcError(replyProxy, requestId, "mute_audio_not_supported")
            return
        }
        val action = payload.optString("action", "").trim()
        mainHandler.post {
            runCatching {
                when (action) {
                    "get_state" -> {
                        val muted = binding.page.isAudioMuted() ?: false
                        audioMuteStates[sessionId] = muted
                        postRpcSuccess(
                            replyProxy,
                            requestId,
                            JSONObject().put("stateJson", JSONObject().put("muted", muted).toString())
                        )
                    }
                    "set_mute" -> {
                        val muted = payload.optBoolean("muted", false)
                        binding.page.setAudioMuted(muted)
                        audioMuteStates[sessionId] = muted
                        dispatchHostEvent(
                            sessionId,
                            "audio_state_changed",
                            JSONObject().put("muted", muted)
                        )
                        postRpcSuccess(
                            replyProxy,
                            requestId,
                            JSONObject().put("stateJson", JSONObject().put("muted", muted).toString())
                        )
                    }
                    else -> postRpcError(replyProxy, requestId, "unsupported_audio_action")
                }
            }.onFailure { error ->
                postRpcError(replyProxy, requestId, error.message ?: "gm_audio_failed")
            }
        }
    }

    private fun handleRegisterWebRequest(
        binding: SessionBinding,
        document: String,
        origin: String,
        isMainFrame: Boolean,
        /** The script as the document held it when the message came. */
        grant: GrantedScript?,
        payload: JSONObject,
        replyProxy: BrowserMessageReply,
        requestId: String
    ) {
        val sessionId = binding.sessionId
        val scriptId = payload.optLong("scriptId")
        val source = payload.optString("source", "runtime").ifBlank { "runtime" }
        runCatching {
            // Checked again under the lock scripts are taken back under: a script turned off,
            // updated or given again meanwhile adds no rules. The rules stay only while the
            // document holds the script as it was.
            val alive = { sessionBindings[sessionId] === binding && binding.scripts(document, origin)[scriptId] === grant }
            val registrationId =
                synchronized(scriptsLock) {
                    if (scriptId <= 0L || grant == null || !alive()) {
                        null
                    } else {
                        val metadata = grant.metadata
                        // Declared rules are taken from the installed script, never from what the page sends.
                        val rulesJson =
                            if (source == "metadata") {
                                metadataRulesJson(metadata?.webRequestRules.orEmpty())
                            } else {
                                payload.optString("rulesJson", "[]")
                            }
                        webRequestEngine.register(
                            scriptId = scriptId,
                            sessionId = sessionId,
                            rulesJson = rulesJson,
                            source = if (source == "metadata") "metadata" else "runtime",
                            reaches = { url ->
                                webOrigin(url) == origin ||
                                    (metadata != null && UserscriptMatcher.isConnectAllowed(metadata, origin, url))
                            },
                            document = document.takeIf { isMainFrame },
                            owner = document,
                            alive = alive
                        )
                    }
                }
            if (registrationId == null) {
                postRpcError(replyProxy, requestId, "invalid_script_id")
                return
            }
            postRpcSuccess(replyProxy, requestId, JSONObject().put("registrationId", registrationId))
        }.onFailure { error ->
            postRpcError(replyProxy, requestId, error.message ?: "gm_web_request_register_failed")
        }
    }

    /** Tells the documents that asked of the rules [matches] of [url]. */
    private fun tellWebRequestMatches(
        sessionId: String,
        matches: List<UserscriptWebRequestMatch>,
        url: String,
        type: String
    ) {
        matches.forEach { match ->
            val document = match.document ?: return@forEach
            dispatchHostEvent(
                sessionId,
                "web_request_event",
                JSONObject()
                    .put("registrationId", match.registrationId)
                    .put("scriptId", match.scriptId)
                    .put("url", url)
                    .put("type", type)
                    .put("source", match.source),
                requireDocument = document
            )
        }
    }

    /** The rules a script declared with @webRequest, read as the page runtime reads them. */
    private fun metadataRulesJson(rules: List<String>): String {
        val array = org.json.JSONArray()
        rules.forEach { rule ->
            val trimmed = rule.trim()
            val looksLikeJson =
                (trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))
            when (val parsed = if (looksLikeJson) runCatching { org.json.JSONTokener(trimmed).nextValue() }.getOrNull() else null) {
                is org.json.JSONArray -> for (index in 0 until parsed.length()) array.put(parsed.get(index))
                is JSONObject -> array.put(parsed)
                else -> array.put(rule)
            }
        }
        return array.toString()
    }

    private fun handleUnregisterWebRequest(
        sessionId: String,
        document: String,
        granted: Map<Long, GrantedScript>,
        payload: JSONObject,
        replyProxy: BrowserMessageReply,
        requestId: String
    ) {
        val registrationId = payload.optString("registrationId", "").trim()
        if (registrationId.isBlank()) {
            postRpcError(replyProxy, requestId, "invalid_registration_id")
            return
        }
        // Only rules this very document added, of a script it was given.
        val owner = webRequestEngine.registration(registrationId)
        if (owner == null || owner.sessionId != sessionId || owner.owner != document || owner.scriptId !in granted) {
            postRpcError(replyProxy, requestId, "registration_not_found")
            return
        }
        val removed = webRequestEngine.unregister(registrationId)
        if (removed) {
            postRpcSuccess(replyProxy, requestId, JSONObject().put("registrationId", registrationId))
        } else {
            postRpcError(replyProxy, requestId, "registration_not_found")
        }
    }

    private fun handleXmlHttpRequest(
        binding: SessionBinding,
        document: String,
        /** The script as the document held it when the message came. */
        grant: GrantedScript?,
        payload: JSONObject,
        replyProxy: BrowserMessageReply,
        requestId: String,
        origin: String
    ) {
        val sessionId = binding.sessionId
        val gmRequestId = payload.optString("requestId", "").trim()
        val scriptId = payload.optLong("scriptId")
        val targetUrl = payload.optString("url", "").trim()
        // @connect is judged from the frame's own origin; the page's address only when it is in it.
        val pageUrl = payload.optString("pageUrl", "").trim().takeIf { webOrigin(it) == origin } ?: origin
        if (gmRequestId.isBlank() || scriptId <= 0L || targetUrl.isBlank()) {
            postRpcError(replyProxy, requestId, "invalid_xhr_request")
            postXhrRefused(replyProxy, gmRequestId, targetUrl)
            return
        }
        // Known right away, before anything is looked up, so an abort that follows finds it.
        val requestKey = "$sessionId:$document:$gmRequestId"
        val request = XhrRequest()
        if (xhrRequests.putIfAbsent(requestKey, request) != null) {
            postRpcError(replyProxy, requestId, "duplicate_request_id")
            return
        }
        scope.launch {
            var started = false
            try {
                val installed = repository.getInstalledScript(scriptId)
                if (installed == null || !installed.enabled) {
                    postRpcError(replyProxy, requestId, "userscript_not_found")
                    postXhrRefused(replyProxy, gmRequestId, targetUrl)
                    return@launch
                }
                // The document still holds the script as it did: one turned off, updated or given
                // again while this was looked up or sent sends nothing more, now or at any redirect.
                val stillGranted = {
                    grant != null && sessionBindings[sessionId] === binding && binding.scripts(document, origin)[scriptId] === grant
                }
                if (!stillGranted()) {
                    postRpcError(replyProxy, requestId, "not_allowed")
                    postXhrRefused(replyProxy, gmRequestId, targetUrl)
                    return@launch
                }
                // The script as it was given to the document; the same once it is checked above.
                val metadata = grant?.metadata ?: installed.toMetadata()
                if (!UserscriptMatcher.isConnectAllowed(metadata, pageUrl, targetUrl)) {
                    repository.log(scriptId, "error", pageUrl, "GM_xmlhttpRequest blocked by @connect: $targetUrl")
                    postRpcError(replyProxy, requestId, "connect_not_allowed")
                    postXhrRefused(replyProxy, gmRequestId, targetUrl)
                    return@launch
                }
                if (request.canceled) {
                    postRpcError(replyProxy, requestId, "aborted")
                    return@launch
                }
                runCatching {
                    startXmlHttpRequest(sessionId, document, requestKey, request, gmRequestId, scriptId, pageUrl, metadata, stillGranted, payload, replyProxy)
                    started = true
                    postRpcSuccess(replyProxy, requestId, JSONObject().put("accepted", true))
                }.onFailure { error ->
                    repository.log(scriptId, "error", pageUrl, error.message ?: "GM_xmlhttpRequest failed")
                    postRpcError(replyProxy, requestId, error.message ?: "xhr_start_failed")
                    postXhrRefused(replyProxy, gmRequestId, targetUrl)
                }
            } finally {
                if (!started) xhrRequests.remove(requestKey, request)
            }
        }
    }

    /** Ends a GM_xmlhttpRequest that was not sent, as a network error, so its callbacks run. */
    private fun postXhrRefused(
        replyProxy: BrowserMessageReply,
        gmRequestId: String,
        url: String
    ) {
        if (gmRequestId.isBlank()) {
            return
        }
        val errorPayload =
            JSONObject()
                .put("status", 0)
                .put("statusText", "not_allowed")
                .put("readyState", 4)
                .put("finalUrl", url)
                .put("responseText", "")
        postXhrEvent(replyProxy, gmRequestId, "readystatechange", terminal = false, payload = JSONObject(errorPayload.toString()))
        postXhrEvent(replyProxy, gmRequestId, "error", terminal = false, payload = errorPayload)
        postXhrEvent(replyProxy, gmRequestId, "loadend", terminal = true, payload = errorPayload)
    }

    private fun startXmlHttpRequest(
        sessionId: String,
        document: String,
        requestKey: String,
        request: XhrRequest,
        gmRequestId: String,
        scriptId: Long,
        pageUrl: String,
        metadata: ParsedUserscriptMetadata,
        stillGranted: () -> Boolean,
        payload: JSONObject,
        replyProxy: BrowserMessageReply
    ) {
        val method = payload.optString("method", "GET").uppercase()
        val url = payload.optString("url", "")
        val responseType = payload.optString("responseType", "text")
        val timeoutMs = payload.optLong("timeoutMs", 0L).coerceAtLeast(0L)
        val anonymous = payload.optBoolean("anonymous", false)
        val bodyData = payload.opt("data")
        val requestType = payload.optString("requestType", "xhr").ifBlank { "xhr" }
        val headers = jsonObjectToMap(payload.optJSONObject("headers")).toMutableMap()
        // The rules of this tab's scripts, and those this document itself added.
        val webRequestResolution = webRequestEngine.resolve(sessionId, url, requestType, requester = document)
        tellWebRequestMatches(sessionId, webRequestResolution.matches, url, requestType)
        val action = webRequestResolution.mergedAction
        if (action.cancel) {
            throw IllegalStateException("GM_webRequest canceled request")
        }
        val targetUrl = action.redirectUrl ?: url
        headers.putAll(action.requestHeaders)
        // Rules this document added that change the request bound every hop to where it could
        // send it itself, while it still holds the script; rules that only watch bind nothing.
        val changedBy =
            webRequestResolution.matches
                .filter { it.source != "metadata" && it.action.requiresInterception() }
                .distinctBy { it.registrationId }
                .map { it.permits }

        // Every hop, the first included, is checked against @connect before it is sent; cookies
        // are added per hop; the script's own credentials go only to the origin it named.
        val policy =
            HopPolicy(
                firstOrigin = url.toHttpUrlOrNull()?.let { originOf(it) }.orEmpty(),
                allows = { hopUrl ->
                    val hop = hopUrl.toString()
                    stillGranted() && UserscriptMatcher.isConnectAllowed(metadata, pageUrl, hop) && changedBy.all { it(hop) }
                },
                cookies = if (anonymous) null else tabCookieStore(sessionId)
            )
        val requestBuilder = Request.Builder().url(targetUrl).tag(HopPolicy::class.java, policy)
        headers.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }

        val requestBody =
            if (method == "GET" || method == "HEAD") {
                null
            } else {
                val bodyText =
                    when (bodyData) {
                        null, JSONObject.NULL -> ""
                        is JSONObject, is org.json.JSONArray -> bodyData.toString()
                        else -> bodyData.toString()
                    }
                val mediaType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.toMediaTypeOrNull()
                bodyText.toRequestBody(mediaType)
            }
        requestBuilder.method(method, requestBody)

        val call = requestClient.newCall(requestBuilder.build())
        if (timeoutMs > 0L) {
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        }
        request.bind(call)
        postXhrEvent(
            replyProxy = replyProxy,
            requestId = gmRequestId,
            eventType = "readystatechange",
            terminal = false,
            payload =
                JSONObject()
                    .put("readyState", 1)
                    .put("status", 0)
                    .put("statusText", "")
                    .put("finalUrl", targetUrl)
        )
        postXhrEvent(
            replyProxy = replyProxy,
            requestId = gmRequestId,
            eventType = "loadstart",
            terminal = false,
            payload =
                JSONObject()
                    .put("readyState", 1)
                    .put("status", 0)
                    .put("statusText", "")
                    .put("finalUrl", targetUrl)
        )

        scope.launch {
            try {
                val response = call.execute()
                val body = response.body
                val total = body?.contentLength()?.takeIf { it >= 0L } ?: 0L
                val output = ByteArrayOutputStream()
                if (body != null) {
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var loaded = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) {
                                break
                            }
                            output.write(buffer, 0, count)
                            loaded += count
                            postXhrEvent(
                                replyProxy = replyProxy,
                                requestId = gmRequestId,
                                eventType = "readystatechange",
                                terminal = false,
                                payload =
                                    JSONObject()
                                        .put("loaded", loaded)
                                        .put("total", total)
                                        .put("readyState", 3)
                            )
                            postXhrEvent(
                                replyProxy = replyProxy,
                                requestId = gmRequestId,
                                eventType = "progress",
                                terminal = false,
                                payload =
                                    JSONObject()
                                        .put("loaded", loaded)
                                        .put("total", total)
                                        .put("readyState", 3)
                            )
                        }
                    }
                }
                val bytes = output.toByteArray()
                val headersJson = JSONObject()
                response.headers.toMultimap().forEach { (key, values) ->
                    headersJson.put(key, values.joinToString(", "))
                }
                val payloadJson =
                    JSONObject()
                        .put("status", response.code)
                        .put("statusText", response.message)
                        .put("readyState", 4)
                        .put("headers", headersJson)
                        .put("finalUrl", response.request.url.toString())
                        .put("loaded", bytes.size.toLong())
                        .put("total", total)
                when (responseType.lowercase()) {
                    "arraybuffer", "blob" -> {
                        val resolvedBytes =
                            action.responseBody?.toByteArray(Charsets.UTF_8)
                                ?: bytes
                        payloadJson.put(
                            "response",
                            Base64.encodeToString(resolvedBytes, Base64.NO_WRAP)
                        )
                        payloadJson.put("responseEncoding", "base64")
                        payloadJson.put("responseType", responseType.lowercase())
                    }
                    else -> {
                        val resolvedText = action.responseBody ?: bytes.toString(Charsets.UTF_8)
                        payloadJson.put("responseText", resolvedText)
                        payloadJson.put("response", resolvedText)
                    }
                }
                action.responseHeaders.forEach { (key, value) ->
                    headersJson.put(key, value)
                }
                postXhrEvent(
                    replyProxy = replyProxy,
                    requestId = gmRequestId,
                    eventType = "readystatechange",
                    terminal = false,
                    payload = JSONObject(payloadJson.toString())
                )
                postXhrEvent(
                    replyProxy = replyProxy,
                    requestId = gmRequestId,
                    eventType = "load",
                    terminal = false,
                    payload = payloadJson
                )
                postXhrEvent(
                    replyProxy = replyProxy,
                    requestId = gmRequestId,
                    eventType = "loadend",
                    terminal = true,
                    payload = payloadJson
                )
                response.close()
            } catch (error: Throwable) {
                if (request.canceled) {
                    return@launch
                }
                val eventType = if (error is java.io.InterruptedIOException) "timeout" else "error"
                val errorPayload =
                    JSONObject()
                        .put("status", 0)
                        .put("statusText", error.message ?: eventType)
                        .put("readyState", 4)
                        .put("finalUrl", payload.optString("url", ""))
                        .put("responseText", "")
                postXhrEvent(
                    replyProxy = replyProxy,
                    requestId = gmRequestId,
                    eventType = "readystatechange",
                    terminal = false,
                    payload = JSONObject(errorPayload.toString())
                )
                postXhrEvent(
                    replyProxy = replyProxy,
                    requestId = gmRequestId,
                    eventType = eventType,
                    terminal = false,
                    payload = errorPayload
                )
                postXhrEvent(
                    replyProxy = replyProxy,
                    requestId = gmRequestId,
                    eventType = "loadend",
                    terminal = true,
                    payload = errorPayload
                )
                repository.log(scriptId, "error", pageUrl, error.message ?: "GM_xmlhttpRequest failed")
            } finally {
                xhrRequests.remove(requestKey, request)
            }
        }
    }

    private fun jsonObjectToMap(raw: JSONObject?): Map<String, String> {
        if (raw == null) {
            return emptyMap()
        }
        return buildMap {
            raw.keys().forEach { key ->
                put(key, raw.optString(key, ""))
            }
        }
    }

    private fun rebuildAllSessionBaselines() {
        sessionPageStates.keys.forEach { sessionId ->
            rebuildSessionBaseline(sessionId)
        }
    }

    private fun rebuildSessionBaseline(sessionId: String) {
        val pageState = sessionPageStates[sessionId] ?: return
        val scripts = uiStore.state.value.installedScripts
        val currentPageUrl = pageState.pageUrl
        val existing = LinkedHashMap(pageState.scriptStatuses)
        pageState.scriptStatuses.clear()
        scripts.forEach { script ->
            pageState.scriptStatuses[script.id] = baselineStatus(script, currentPageUrl)
        }
        existing.forEach { (scriptId, status) ->
            val baseline = pageState.scriptStatuses[scriptId] ?: return@forEach
            if (baseline.state == UserscriptPageRuntimeState.QUEUED ||
                baseline.state == UserscriptPageRuntimeState.RUNNING ||
                baseline.state == UserscriptPageRuntimeState.SUCCESS ||
                baseline.state == UserscriptPageRuntimeState.ERROR
            ) {
                pageState.scriptStatuses[scriptId] = status
            }
        }
        publishVisibleStatuses()
    }

    private fun baselineStatus(
        script: UserscriptListItem,
        pageUrl: String
    ): UserscriptPageRuntimeStatus {
        return when {
            !script.enabled ->
                UserscriptPageRuntimeStatus(UserscriptPageRuntimeState.DISABLED)
            script.blockedReasons.isNotEmpty() ->
                UserscriptPageRuntimeStatus(
                    UserscriptPageRuntimeState.UNSUPPORTED,
                    detail = script.blockedReasons.joinToString()
                )
            pageUrl.isBlank() || pageUrl == "about:blank" ->
                UserscriptPageRuntimeStatus(UserscriptPageRuntimeState.NOT_MATCHED)
            UserscriptMatcher.matches(
                metadata = script.toMetadata(),
                pageUrl = pageUrl,
                isTopFrame = true
            ) ->
                UserscriptPageRuntimeStatus(UserscriptPageRuntimeState.QUEUED)
            else ->
                UserscriptPageRuntimeStatus(UserscriptPageRuntimeState.NOT_MATCHED)
        }
    }

    private fun upsertRuntimeStatus(
        sessionId: String,
        scriptId: Long,
        rawState: String,
        detail: String?
    ) {
        val mappedState =
            when (rawState.trim().lowercase()) {
                "running" -> UserscriptPageRuntimeState.RUNNING
                "success" -> UserscriptPageRuntimeState.SUCCESS
                "error" -> UserscriptPageRuntimeState.ERROR
                else -> UserscriptPageRuntimeState.QUEUED
            }
        val pageState = sessionPageStates.getOrPut(sessionId) { SessionPageState() }
        pageState.scriptStatuses[scriptId] =
            UserscriptPageRuntimeStatus(
                state = mappedState,
                detail = detail
            )
        publishVisibleStatuses()
    }

    private fun publishVisibleStatuses() {
        val visibleStatuses =
            visibleSessionId
                ?.let { sessionPageStates[it]?.scriptStatuses?.toMap() }
                .orEmpty()
        uiStore.updateCurrentPageStatuses(visibleStatuses)
    }

    private fun dispatchHostEvent(
        sessionId: String,
        eventType: String,
        payload: JSONObject,
        /** Only while the main frame still shows a page of this origin. */
        requireOrigin: String? = null,
        /** Only to this document of the main frame (its runtime's id), never to the next one. */
        requireDocument: String? = null
    ) {
        val binding = sessionBindings[sessionId] ?: return
        val rawMessage =
            JSONObject()
                .put("type", eventType)
                .put("payload", payload)
                .toString()
        val escapedMessage = JSONObject.quote(rawMessage)
        val documentCheck =
            requireDocument?.let { "if (runtime.documentId !== ${JSONObject.quote(it)}) { return; }" }.orEmpty()
        mainHandler.post {
            if (requireOrigin != null && webOrigin(binding.page.url) != requireOrigin) {
                return@post
            }
            runCatching {
                binding.page.evaluateJavascript(
                    """
                    (function() {
                        "use strict";
                        const runtime = window.__operitUserscriptRuntime;
                        if (!runtime || typeof runtime.dispatchHostEvent !== "function") {
                            return;
                        }
                        $documentCheck
                        runtime.dispatchHostEvent(JSON.parse($escapedMessage));
                    })();
                    """.trimIndent(),
                    null
                )
            }.onFailure { error ->
                AppLogger.w(TAG, "Failed to dispatch userscript host event: ${error.message}")
            }
        }
    }

    private fun resolveWebRequestType(request: WebResourceRequest): String {
        if (request.isForMainFrame) {
            return "main_frame"
        }
        val lowerUrl = request.url?.toString().orEmpty().lowercase()
        val accept = request.requestHeaders?.entries?.firstOrNull {
            it.key.equals("Accept", ignoreCase = true)
        }?.value?.lowercase().orEmpty()
        return when {
            accept.contains("text/css") || lowerUrl.endsWith(".css") -> "stylesheet"
            accept.contains("javascript") || lowerUrl.endsWith(".js") -> "script"
            accept.contains("image/") || lowerUrl.endsWith(".png") || lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg") ||
                lowerUrl.endsWith(".gif") || lowerUrl.endsWith(".svg") -> "image"
            accept.contains("font/") || lowerUrl.endsWith(".woff") || lowerUrl.endsWith(".woff2") || lowerUrl.endsWith(".ttf") -> "font"
            accept.contains("video/") || accept.contains("audio/") -> "media"
            accept.contains("application/json") || accept.contains("text/plain") -> "fetch"
            else -> "other"
        }
    }

    private fun UserscriptListItem.toMetadata(): ParsedUserscriptMetadata =
        ParsedUserscriptMetadata(
            name = name,
            namespace = namespace,
            version = version,
            description = description,
            homepage = homepage,
            website = website,
            supportUrl = supportUrl,
            downloadUrl = downloadUrl,
            updateUrl = updateUrl,
            grants = grants,
            matches = matches,
            includes = includes,
            excludes = excludes,
            excludeMatches = excludeMatches,
            connects = connects,
            requires = requires,
            resources = resources,
            icons = icons,
            tags = tags,
            sandbox = sandbox,
            runIn = runIn,
            unwrap = unwrap,
            webRequestRules = webRequestRules
        )

    private fun postRpcSuccess(
        replyProxy: BrowserMessageReply,
        requestId: String,
        payload: JSONObject
    ) {
        postBridgeMessage(
            replyProxy,
            JSONObject()
                .put("type", "rpc_response")
                .put("requestId", requestId)
                .put("payload", payload)
        )
    }

    private fun postRpcError(
        replyProxy: BrowserMessageReply,
        requestId: String,
        error: String
    ) {
        postBridgeMessage(
            replyProxy,
            JSONObject()
                .put("type", "rpc_response")
                .put("requestId", requestId)
                .put("error", error)
        )
    }

    private fun postXhrEvent(
        replyProxy: BrowserMessageReply,
        requestId: String,
        eventType: String,
        terminal: Boolean,
        payload: JSONObject
    ) {
        postBridgeMessage(
            replyProxy,
            JSONObject()
                .put("type", "xhr_event")
                .put("requestId", requestId)
                .put("eventType", eventType)
                .put("terminal", terminal)
                .put("payload", payload)
        )
    }

    private fun postBridgeMessage(
        replyProxy: BrowserMessageReply,
        payload: JSONObject
    ) {
        mainHandler.post {
            runCatching { replyProxy.postMessage(payload.toString()) }
                .onFailure { AppLogger.w(TAG, "Failed to post userscript bridge message: ${it.message}") }
        }
    }
}
