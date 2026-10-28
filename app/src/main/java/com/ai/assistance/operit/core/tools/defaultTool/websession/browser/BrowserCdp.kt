package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.webkit.WebView
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools
import com.ai.assistance.operit.util.AppLogger
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

private const val CDP_TAG = "BrowserCdp"

internal class CdpException(message: String) : IOException(message)

/**
 * The DevTools protocol of the app's own WebViews. Android opens it on a local socket that only
 * this app and an authorized ADB computer can reach; other apps on the phone cannot connect.
 */
internal object BrowserCdp {
    private val debuggingEnabled = AtomicBoolean(false)
    private val connections = ConcurrentHashMap<String, CdpConnection>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val attachLocks = ConcurrentHashMap<String, Any>()
    private val screenshotFailedAt = ConcurrentHashMap<String, Long>()

    private val socketName: String get() = "webview_devtools_remote_${Process.myPid()}"

    /** Main thread. Turns on the DevTools socket once for the app's WebViews. */
    fun enableDebuggingOnMain() {
        if (debuggingEnabled.compareAndSet(false, true)) {
            runCatching { WebView.setWebContentsDebuggingEnabled(true) }
                .onFailure {
                    debuggingEnabled.set(false)
                    AppLogger.w(CDP_TAG, "DevTools could not be turned on: ${it.message}")
                }
        }
    }

    /**
     * The tab's DevTools connection, attaching on first use. Returns null when DevTools is not
     * available, so callers keep their WebView fallback. Never call on the main thread: finding the
     * tab waits for the page to answer.
     */
    fun connection(tools: StandardBrowserSessionTools, session: BrowserToolSession): CdpConnection? {
        if (Looper.myLooper() == Looper.getMainLooper() || !debuggingEnabled.get()) return null
        connections[session.id]?.takeIf { it.isOpen }?.let { return it }
        val lock = attachLocks.getOrPut(session.id) { Any() }
        synchronized(lock) {
            connections[session.id]?.takeIf { it.isOpen }?.let { return it }
            if (StandardBrowserSessionTools.sessions[session.id] !== session) return null
            if ((retryAfter[session.id] ?: 0L) > System.currentTimeMillis()) return null
            val attached =
                runCatching { attach(tools, session) }
                    .onFailure { AppLogger.w(CDP_TAG, "DevTools attach failed: ${it.message}") }
                    .getOrNull()
            if (attached == null) {
                retryAfter[session.id] = System.currentTimeMillis() + 15_000L
                return null
            }
            retryAfter.remove(session.id)
            connections[session.id] = attached
            // A tab closed while attaching must not keep its socket.
            if (StandardBrowserSessionTools.sessions[session.id] !== session) {
                forget(session.id)
                return null
            }
            return attached
        }
    }

    fun forget(sessionId: String) {
        connections.remove(sessionId)?.close()
        retryAfter.remove(sessionId)
        attachLocks.remove(sessionId)
        screenshotFailedAt.remove(sessionId)
    }

    /** A tab whose page is not being drawn (for example, a hidden browser) times out; skip it for a minute. */
    fun screenshotsWork(sessionId: String): Boolean =
        (screenshotFailedAt[sessionId] ?: 0L) + 60_000L < System.currentTimeMillis()

    fun markScreenshotFailed(sessionId: String) {
        screenshotFailedAt[sessionId] = System.currentTimeMillis()
    }

    private fun attach(tools: StandardBrowserSessionTools, session: BrowserToolSession): CdpConnection? {
        val tag = "operit-" + UUID.randomUUID().toString()
        if (!tagPage(session.webView, tag)) return null
        val taken = connections.values.filter { it.isOpen }.map { it.targetId }.toSet()
        val targets = JSONArray(CdpConnection.httpGet(socketName, "/json/list"))
        val candidates =
            (0 until targets.length())
                .mapNotNull { targets.optJSONObject(it) }
                .filter { it.optString("type") == "page" && it.optString("id") !in taken }
                .sortedByDescending { it.optString("url") == session.currentUrl }
        for (target in candidates) {
            val targetId = target.optString("id")
            val path =
                target.optString("webSocketDebuggerUrl").substringAfter("ws://", "").substringAfter('/', "")
                    .takeIf { it.isNotBlank() }?.let { "/$it" } ?: "/devtools/page/$targetId"
            val connection = runCatching { CdpConnection.open(socketName, path, targetId) }.getOrNull() ?: continue
            val found =
                runCatching {
                    connection.send(
                        "Runtime.evaluate",
                        JSONObject().put("expression", "window.__operitCdpTag || ''").put("returnByValue", true),
                        2_000L
                    ).optJSONObject("result")?.optString("value")
                }.getOrNull()
            if (found == tag) {
                listenForNetwork(tools, session, connection)
                return connection
            }
            connection.close()
        }
        return null
    }

    /** Marks the page so its DevTools target can be told apart from other tabs. */
    private fun tagPage(webView: WebView, tag: String): Boolean {
        val latch = CountDownLatch(1)
        StandardBrowserSessionTools.mainHandler.post {
            runCatching {
                webView.evaluateJavascript("window.__operitCdpTag = '$tag'; true") { latch.countDown() }
            }.onFailure { latch.countDown() }
        }
        return latch.await(3, TimeUnit.SECONDS)
    }

    private fun listenForNetwork(
        tools: StandardBrowserSessionTools,
        session: BrowserToolSession,
        connection: CdpConnection
    ) {
        val methods = object : LinkedHashMap<String, String>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 300
        }
        connection.eventListener = { method, params ->
            val requestId = params.optString("requestId")
            when (method) {
                "Network.requestWillBeSent" -> {
                    val request = params.optJSONObject("request")
                    params.optJSONObject("redirectResponse")?.let { redirect ->
                        // The redirect answers the earlier hop, whose method can differ (POST then 303 GET).
                        val hopMethod = synchronized(methods) { methods[requestId] } ?: request?.optString("method")
                        tools.recordNetworkOutcome(
                            session, requestId, hopMethod, redirect.optString("url"),
                            params.optString("type"), redirect.optInt("status"), redirect.optString("mimeType"), null,
                            isRedirectHop = true
                        )
                    }
                    if (request != null) synchronized(methods) { methods[requestId] = request.optString("method", "GET") }
                }
                "Network.responseReceived" -> {
                    params.optJSONObject("response")?.let { response ->
                        tools.recordNetworkOutcome(
                            session, requestId, synchronized(methods) { methods[requestId] }, response.optString("url"),
                            params.optString("type"), response.optInt("status"), response.optString("mimeType"), null
                        )
                    }
                }
                "Network.loadingFailed" -> {
                    val requestMethod = synchronized(methods) { methods.remove(requestId) }
                    connection.urlFor(requestId)?.let { url ->
                        val reason =
                            params.optString("blockedReason").takeIf { it.isNotBlank() }?.let { "blocked: $it" }
                                ?: params.optString("errorText").ifBlank { "failed" }
                        tools.recordNetworkOutcome(session, requestId, requestMethod, url, params.optString("type"), null, null, reason)
                    }
                }
                "Network.loadingFinished" -> {
                    synchronized(methods) { methods.remove(requestId) }
                    connection.urlFor(requestId)
                }
                else -> {
                    // Request events are already summarized by browser_network_requests; keep the rest
                    // (debugger pauses, WebSocket frames, console calls...) for browser_devtools to read.
                    if (!method.startsWith("Network.") || method.startsWith("Network.webSocket")) {
                        connection.recordEvent(method, params)
                    }
                }
            }
        }
        // Small buffers: bodies are only kept for a short look through the DevTools tool.
        runCatching {
            connection.send(
                "Network.enable",
                JSONObject().put("maxTotalBufferSize", 8_000_000).put("maxResourceBufferSize", 2_000_000),
                3_000L
            )
        }.onFailure { AppLogger.w(CDP_TAG, "Network events unavailable: ${it.message}") }
    }

    /** The visible page as DevTools composites it, in device pixels; null when it cannot. */
    fun captureViewportPng(connection: CdpConnection): ByteArray? =
        runCatching {
            val result = connection.send("Page.captureScreenshot", JSONObject().put("format", "png"), 5_000L)
            Base64.decode(result.getString("data"), Base64.DEFAULT)
        }.getOrNull()
}

/** One WebSocket to one DevTools target, over the app's abstract local socket. */
internal class CdpConnection private constructor(
    private val socket: LocalSocket,
    val targetId: String
) {
    private val input: InputStream = BufferedInputStream(socket.inputStream, 64 * 1024)
    private val output: OutputStream = socket.outputStream
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
    private val writeLock = Any()
    private val random = SecureRandom()
    private val urls = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 300
    }
    @Volatile private var closed = false
    @Volatile var eventListener: ((String, JSONObject) -> Unit)? = null

    val isOpen: Boolean get() = !closed

    /** The URL a request was sent to, forgotten once read. */
    fun urlFor(requestId: String): String? = synchronized(urls) { urls.remove(requestId) }

    private val recentEvents = ArrayDeque<String>()
    private var droppedEvents = 0

    /** Keeps the latest events, each shortened, until browser_devtools reads them. */
    fun recordEvent(method: String, params: JSONObject) {
        val raw = params.toString()
        val shown = if (raw.length > 2_000) raw.take(2_000) + "...(${raw.length} chars)" else raw
        val line = "${System.currentTimeMillis()} $method $shown"
        synchronized(recentEvents) {
            recentEvents.addLast(line)
            while (recentEvents.size > 200) {
                recentEvents.removeFirst()
                droppedEvents++
            }
        }
    }

    /** Events since the last read, oldest first, and how many were dropped because there were too many. */
    fun drainEvents(): Pair<List<String>, Int> =
        synchronized(recentEvents) {
            val events = recentEvents.toList()
            val dropped = droppedEvents
            recentEvents.clear()
            droppedEvents = 0
            events to dropped
        }

    /** Sends one command and waits for its answer; a DevTools error becomes [CdpException]. */
    fun send(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 10_000L): JSONObject {
        if (closed) throw CdpException("DevTools connection closed")
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        try {
            writeText(JSONObject().put("id", id).put("method", method).put("params", params).toString())
            val reply =
                try {
                    future.get(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    throw CdpException("DevTools did not answer $method in ${timeoutMs}ms")
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw CdpException(e.cause?.message ?: "DevTools connection closed")
                }
            reply.optJSONObject("error")?.let { throw CdpException(it.optString("message", "DevTools error")) }
            return reply.optJSONObject("result") ?: JSONObject()
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Safe on the main thread: never waits for a write in progress. Closing the socket wakes a
     * blocked reader or writer, and DevTools treats a dropped socket as a normal disconnect.
     */
    fun close(reason: String = "DevTools connection closed") {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        pending.values.forEach { it.completeExceptionally(CdpException(reason)) }
        pending.clear()
    }

    private fun startReader() {
        Thread({
            try {
                while (!closed) {
                    val message = readMessage() ?: break
                    val json = runCatching { JSONObject(message) }.getOrNull() ?: continue
                    if (json.has("id")) {
                        pending[json.optInt("id")]?.complete(json)
                    } else {
                        val method = json.optString("method")
                        val params = json.optJSONObject("params") ?: JSONObject()
                        if (method == "Network.requestWillBeSent") {
                            params.optJSONObject("request")?.optString("url")?.let { url ->
                                synchronized(urls) { urls[params.optString("requestId")] = url }
                            }
                        }
                        runCatching { eventListener?.invoke(method, params) }
                    }
                }
            } catch (e: CdpException) {
                close(e.message ?: "DevTools connection closed")
            } catch (_: Exception) {
            } finally {
                close()
            }
        }, "operit-cdp").apply { isDaemon = true }.start()
    }

    private fun writeText(text: String) {
        synchronized(writeLock) { writeFrame(0x1, text.toByteArray(Charsets.UTF_8)) }
    }

    /** Client frames are always masked, as the WebSocket protocol requires. */
    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                header.write(0x80 or 126)
                header.write(payload.size ushr 8)
                header.write(payload.size and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                val length = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) header.write(((length ushr shift) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        header.write(mask)
        val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() }
        output.write(header.toByteArray())
        output.write(masked)
        output.flush()
    }

    /** One whole text message, joining fragments; answers pings; null when the peer closes. */
    private fun readMessage(): String? {
        val message = ByteArrayOutputStream()
        while (true) {
            val first = readByte()
            val second = readByte()
            val opcode = first and 0x0F
            val fin = (first and 0x80) != 0
            var length = (second and 0x7F).toLong()
            if (length == 126L) {
                length = ((readByte() shl 8) or readByte()).toLong()
            } else if (length == 127L) {
                length = 0L
                repeat(8) { length = (length shl 8) or readByte().toLong() }
            }
            if (length < 0 || message.size() + length > MAX_MESSAGE_BYTES) {
                throw CdpException("DevTools message too large")
            }
            val mask = if ((second and 0x80) != 0) ByteArray(4).also { readFully(it) } else null
            val payload = ByteArray(length.toInt()).also { readFully(it) }
            if (mask != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            }
            when (opcode) {
                0x8 -> return null
                0x9 -> synchronized(writeLock) { writeFrame(0xA, payload) }
                0xA -> Unit
                0x1, 0x0 -> {
                    // A single-frame message is decoded straight from its payload, without another copy.
                    if (fin && message.size() == 0) return String(payload, Charsets.UTF_8)
                    message.write(payload)
                    if (fin) return message.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    private fun readByte(): Int {
        val value = input.read()
        if (value < 0) throw EOFException()
        return value
    }

    private fun readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
    }

    companion object {
        // Enough for a viewport screenshot or a large DOM; bigger replies are refused before they are buffered.
        private const val MAX_MESSAGE_BYTES = 24L * 1024 * 1024

        private fun connect(socketName: String): LocalSocket =
            LocalSocket().apply {
                connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            }

        fun open(socketName: String, path: String, targetId: String): CdpConnection {
            val socket = connect(socketName)
            try {
                socket.soTimeout = 3_000
                val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
                val request =
                    "GET $path HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
                socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                socket.outputStream.flush()
                val head = readHead(socket.inputStream)
                if (!head.startsWith("HTTP/1.1 101")) throw CdpException("DevTools refused the connection")
                socket.soTimeout = 0
                return CdpConnection(socket, targetId).also { it.startReader() }
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }

        /** A plain HTTP GET on the DevTools socket, such as /json/list. */
        fun httpGet(socketName: String, path: String): String {
            val socket = connect(socketName)
            try {
                socket.soTimeout = 3_000
                socket.outputStream.write(
                    "GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
                )
                socket.outputStream.flush()
                val input = socket.inputStream
                val head = readHead(input)
                if (!head.startsWith("HTTP/1.1 200")) {
                    throw CdpException("DevTools answered: ${head.lineSequence().first()}")
                }
                val length =
                    head.lineSequence()
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull()
                val body = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (length == null || body.size() < length) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    body.write(buffer, 0, read)
                    if (body.size() > 4 * 1024 * 1024) throw CdpException("DevTools list too large")
                }
                return body.toString(Charsets.UTF_8.name())
            } finally {
                runCatching { socket.close() }
            }
        }

        /** Reads the response head byte by byte so no WebSocket bytes are swallowed. */
        private fun readHead(input: InputStream): String {
            val head = ByteArrayOutputStream()
            val end = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
            var matched = 0
            while (matched < end.size) {
                val value = input.read()
                if (value < 0) throw EOFException()
                head.write(value)
                matched =
                    when {
                        value.toByte() == end[matched] -> matched + 1
                        value.toByte() == end[0] -> 1
                        else -> 0
                    }
                if (head.size() > 16 * 1024) throw CdpException("DevTools header too large")
            }
            return head.toString(Charsets.US_ASCII.name())
        }
    }
}
