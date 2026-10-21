package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Lets terminal scripts use a saved API key without ever holding it, like Muse's authd surrogates.
 *
 * Orbit gets a surrogate for a key (`ovk.<orbit>.<entry>.<mac>`) and puts it where the key would go,
 * in a header or the query, then sends the request to this loopback proxy as
 * `http://127.0.0.1:<port>/https/<host>/<path>`. The proxy checks the surrogate and that the host is
 * one the key was saved for, swaps in the real key at the last moment, makes the https request, and
 * masks the key in what comes back. A request without a valid surrogate is refused, so this is not
 * an open proxy; the surrogate stops working once the key is deleted or saved again.
 *
 * A small server of its own: the path is forwarded exactly as the script encoded it (`%2F`, `+`).
 */
internal object OrbitVaultBroker {
    private const val PREFERRED_PORT = 47615
    private const val MAX_HEAD = 64 * 1024
    private const val MAX_REQUEST = 10 * 1024 * 1024
    private const val MAX_RESPONSE = 20 * 1024 * 1024
    private val SURROGATE = Regex("ovk\\.([A-Za-z0-9-]{1,64})\\.([A-Za-z0-9-]{1,40})\\.([0-9a-f]{32})")
    private val HOST = Regex("[a-z0-9.-]{3,253}")
    private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
    /** Not forwarded: hop-by-hop headers and the ones the proxy sets itself. */
    private val DROPPED_REQUEST = setOf("host", "connection", "content-length", "content-type", "transfer-encoding",
        "keep-alive", "upgrade", "te", "trailer", "accept-encoding", "expect")
    private val DROPPED_RESPONSE = setOf("connection", "content-length", "transfer-encoding", "keep-alive",
        "content-encoding", "trailer", "upgrade")

    @Volatile private var server: ServerSocket? = null
    private val keys = ConcurrentHashMap<String, ByteArray>()
    /** A few requests at a time; more are told to retry. */
    private val workers = ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, SynchronousQueue()) { task ->
        Thread(task, "OrbitVaultBroker-worker").apply { isDaemon = true }
    }
    /** Closes a connection that outlives its deadline, so a client that stops reading cannot hold a worker. */
    private val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "OrbitVaultBroker-deadline").apply { isDaemon = true }
    }
    private const val CONNECTION_DEADLINE_SECONDS = 240L

    private val client by lazy {
        // Redirects could carry the key to another host, so they are passed back, not followed.
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // The whole call ends before the connection's own deadline, so a slow service cannot hold a worker.
            .callTimeout(200, TimeUnit.SECONDS).build()
    }

    /** The proxy's base address, starting it if needed: append `<host>/<path>`. */
    @Synchronized
    fun base(context: Context): String {
        server?.takeIf { !it.isClosed }?.let { return "http://127.0.0.1:${it.localPort}/https/" }
        val app = context.applicationContext
        val loopback = InetAddress.getByName("127.0.0.1")
        // The same port each time when it is free, so a saved script keeps working after a restart.
        val socket = try { ServerSocket(PREFERRED_PORT, 16, loopback) } catch (e: IOException) { ServerSocket(0, 16, loopback) }
        server = socket
        thread(isDaemon = true, name = "OrbitVaultBroker") {
            while (!socket.isClosed) {
                val peer = try { socket.accept() } catch (e: IOException) { break }
                try {
                    workers.execute { serve(app, peer) }
                } catch (e: RejectedExecutionException) {
                    peer.use { runCatching { write(it, error(503, "The proxy is busy; try again in a moment"), false) } }
                }
            }
            runCatching { socket.close() }
        }
        return "http://127.0.0.1:${socket.localPort}/https/"
    }

    /** The stand-in for one saved API key; saving the key again gives a new one. */
    fun surrogate(context: Context, orbitId: String, entry: OrbitVault.Entry): String =
        "ovk.$orbitId.${entry.id}.${mac(context, orbitId, entry)}"

    /** A random key per Orbit, kept encrypted with its other private stores. */
    private fun macKey(context: Context, orbitId: String): ByteArray = keys[orbitId] ?: synchronized(this) {
        keys[orbitId] ?: run {
            val store = OrbitSecretFile.forOrbit(context, orbitId, "broker")
            val saved = store.read()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
            saved ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.also { bytes ->
                store.write(bytes.joinToString("") { "%02x".format(it) })
            }
        }.also { keys[orbitId] = it }
    }

    private fun mac(context: Context, orbitId: String, entry: OrbitVault.Entry): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey(context, orbitId), "HmacSHA256"))
        return mac.doFinal("$orbitId|${entry.id}|${entry.kind}|${entry.savedAt}".toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
    }

    private class Refused(val status: Int, message: String) : Exception(message)

    private class Incoming(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray?)

    private class Outgoing(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray)

    private fun error(status: Int, message: String) = Outgoing(status, listOf("Content-Type" to "application/json"),
        JSONObject().put("orbit_vault_proxy_error", message).toString().toByteArray())

    private fun serve(context: Context, peer: Socket) {
        val deadline = deadlines.schedule({ runCatching { peer.close() } }, CONNECTION_DEADLINE_SECONDS, TimeUnit.SECONDS)
        try {
            serveUntilDone(context, peer)
        } finally {
            deadline.cancel(false)
            runCatching { peer.close() }
        }
    }

    private fun serveUntilDone(context: Context, peer: Socket) {
        peer.soTimeout = 15_000
        var head = false
        val response = try {
            if (!peer.inetAddress.isLoopbackAddress) throw Refused(403, "Only this phone may use the proxy")
            val incoming = read(BufferedInputStream(peer.getInputStream())) {
                // curl waits for this before sending a larger body.
                peer.getOutputStream().apply { write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); flush() }
            }
            head = incoming.method == "HEAD"
            forward(context, incoming)
        } catch (e: Refused) {
            error(e.status, e.message.orEmpty())
        } catch (e: OrbitVault.TooLarge) {
            error(502, "The response is too large")
        } catch (e: IOException) {
            error(502, "The request to the service failed: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            error(400, e.message ?: e.toString())
        }
        runCatching { write(peer, response, head) }
    }

    private fun read(input: InputStream, sendContinue: () -> Unit): Incoming {
        var used = 0
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) throw Refused(400, "The request ended early")
                if (++used > MAX_HEAD) throw Refused(400, "The request headers are too large")
                if (next == '\n'.code) break
                bytes.write(next)
            }
            return bytes.toString("UTF-8").trimEnd('\r')
        }
        val parts = line().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw Refused(400, "Not an HTTP/1.1 request")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val row = line()
            if (row.isEmpty()) break
            val split = row.indexOf(':')
            if (split <= 0) throw Refused(400, "Bad header line")
            val name = row.substring(0, split).trim().lowercase()
            val value = row.substring(split + 1).trim()
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }
        if (headers["transfer-encoding"] != null) throw Refused(411, "Send the body with a Content-Length (not chunked)")
        val length = headers["content-length"]?.let {
            it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw Refused(400, "Bad Content-Length")
        }
        if (length != null && length > MAX_REQUEST) throw Refused(413, "The request body must be under 10 MB")
        if (length != null && length > 0 && headers["expect"]?.equals("100-continue", true) == true) sendContinue()
        val body = length?.let { n ->
            val bytes = ByteArray(n.toInt())
            var read = 0
            while (read < bytes.size) {
                val got = input.read(bytes, read, bytes.size - read)
                if (got < 0) throw Refused(400, "The request body ended early")
                read += got
            }
            bytes
        }
        return Incoming(parts[0].uppercase(), parts[1], headers, body)
    }

    private fun write(peer: Socket, response: Outgoing, head: Boolean) {
        fun clean(text: String) = text.replace(Regex("[\\r\\n]"), " ")
        val reason = when (response.status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 411 -> "Length Required"
            413 -> "Payload Too Large"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; else -> "Status"
        }
        val text = StringBuilder("HTTP/1.1 ${response.status} $reason\r\n")
        response.headers.forEach { (name, value) -> text.append(clean(name)).append(": ").append(clean(value)).append("\r\n") }
        // HEAD, 204 and 304 carry no body; HEAD passes the service's own Content-Length through.
        val noBody = head || response.status == 204 || response.status == 304
        if (!noBody) text.append("Content-Length: ${response.body.size}\r\n")
        text.append("Connection: close\r\n\r\n")
        val out = peer.getOutputStream()
        out.write(text.toString().toByteArray(Charsets.UTF_8))
        if (!noBody) out.write(response.body)
        out.flush()
    }

    private fun forward(context: Context, incoming: Incoming): Outgoing {
        // Web pages send an Origin; terminal scripts do not. Pages never get to use a key.
        if (incoming.headers.containsKey("origin")) throw Refused(403, "Requests from web pages are not allowed")
        if (incoming.method !in METHODS) throw Refused(400, "Unsupported method ${incoming.method}")
        val target = incoming.target
        if (!target.startsWith("/https/")) throw Refused(404,
            "Use http://127.0.0.1:<port>/https/<host>/<path>; get the address from orbit_vault action terminal")
        if (target.any { it <= ' ' || it.code == 0x7f } || '#' in target) throw Refused(400, "Bad characters in the address")
        val rest = target.removePrefix("/https/")
        val pathAndQuery = rest.substring(rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it })
        val authority = rest.substring(0, rest.length - pathAndQuery.length).lowercase()
        val rawPath = pathAndQuery.substringBefore('?').ifEmpty { "/" }
        val query = pathAndQuery.substringAfter('?', "").takeIf { '?' in pathAndQuery }
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "").takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: throw Refused(400, "Bad port in $authority")
        }
        if (!host.matches(HOST) || !host.contains('.')) throw Refused(400, "Bad host \"$authority\"")

        // Every surrogate in the headers and query must be valid and allowed for this host.
        val headers = incoming.headers.filterKeys { it !in DROPPED_REQUEST && !it.startsWith("proxy-") }
        val found = (headers.values + listOfNotNull(query))
            .flatMap { text -> SURROGATE.findAll(text).map { it.value }.toList() }.distinct()
        if (found.isEmpty()) throw Refused(403,
            "No vault surrogate in the request. Put the surrogate from orbit_vault action terminal where the key goes.")
        val swaps = found.associateWith { resolve(context, it, host) }
        fun swap(text: String) = swaps.entries.fold(text) { acc, (surrogate, entry) -> acc.replace(surrogate, entry.secret) }
        // In the query the key goes in encoded, so characters like + & = keep their meaning.
        fun swapQuery(text: String) = swaps.entries.fold(text) { acc, (surrogate, entry) ->
            acc.replace(surrogate, URLEncoder.encode(entry.secret, "UTF-8").replace("+", "%20"))
        }

        val url = HttpUrl.Builder().scheme("https").host(host).apply { port?.let { port(it) } }
            .encodedPath(rawPath).apply { if (query != null) encodedQuery(swapQuery(query)) }.build()
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) ->
            try {
                builder.addHeader(name, swap(value))
            } catch (e: IllegalArgumentException) {
                throw Refused(400, "Header $name has characters that cannot be sent")
            }
        }
        val mediaType = incoming.headers["content-type"]?.toMediaTypeOrNull()
        val method = incoming.method
        builder.method(method, when {
            method in setOf("GET", "HEAD") -> null
            incoming.body != null -> incoming.body.toRequestBody(mediaType)
            method == "DELETE" -> null
            else -> ByteArray(0).toRequestBody(mediaType)
        })

        val secrets = swaps.values.map { it.secret }
        // A failure message can quote what the service sent back; it is masked like the response.
        val call = try { client.newCall(builder.build()).execute() } catch (e: IOException) {
            throw Refused(502, "The request to the service failed: " +
                OrbitVault.redact(e.message ?: e.javaClass.simpleName, secrets))
        }
        call.use { response ->
            val bytes = response.body?.byteStream()?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_RESPONSE) throw Refused(502, "The response is larger than 20 MB")
                }
                out.toByteArray()
            } ?: ByteArray(0)
            val masked = OrbitVault.redact(bytes, secrets, MAX_RESPONSE)
            // HEAD keeps the service's length; other replies get the length of what is sent.
            val headersOut = response.headers.names()
                .filter { it.lowercase() !in DROPPED_RESPONSE || (method == "HEAD" && it.equals("content-length", true)) }
                .map { name -> name to OrbitVault.redact(response.headers(name).joinToString(", "), secrets) } +
                ("X-Orbit-Vault" to "proxied")
            return Outgoing(response.code, headersOut, masked)
        }
    }

    private fun resolve(context: Context, surrogate: String, host: String): OrbitVault.Entry {
        val match = SURROGATE.matchEntire(surrogate) ?: throw Refused(403, "Invalid surrogate")
        val (orbitId, entryId, given) = match.destructured
        val entries = runBlocking {
            OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == orbitId && !it.deleting }
                ?.let { OrbitVault.entries(context, it.id) }
        } ?: throw Refused(403, "This surrogate's Orbit no longer exists")
        val entry = entries.firstOrNull { it.id == entryId }
            ?: throw Refused(403, "This key is no longer in the vault; it was deleted or replaced")
        if (!MessageDigest.isEqual(mac(context, orbitId, entry).toByteArray(), given.toByteArray())) {
            throw Refused(403, "This surrogate is out of date; get a new one with orbit_vault action terminal")
        }
        if (entry.kind != OrbitVault.API_KEY) throw Refused(403, "Only saved API keys can be used from the terminal")
        if (entry.apiHosts.none { OrbitVault.matchesHost(host, it) }) {
            throw Refused(403, "This key may only be sent to ${entry.apiHosts.joinToString()}")
        }
        return entry
    }
}
