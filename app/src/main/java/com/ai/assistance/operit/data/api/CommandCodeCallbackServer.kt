package com.ai.assistance.operit.data.api

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Browser sends a POST, unlike the authorization-code redirects used by the other accounts. */
internal class CommandCodeCallbackServer private constructor(
    private val socket: ServerSocket,
    val state: String,
) : Closeable {
    val authorizationUrl: String
        get() = "https://commandcode.ai/studio/auth/cli?callback=" +
            URLEncoder.encode("http://127.0.0.1:${socket.localPort}/callback", "UTF-8") +
            "&state=" + URLEncoder.encode(state, "UTF-8")

    suspend fun awaitKey(): String = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        while (true) {
            currentCoroutineContext().ensureActive()
            val connection = try { socket.accept() } catch (_: SocketTimeoutException) { continue }
            connection.use { peer ->
                peer.soTimeout = 2000
                try {
                    val input = peer.getInputStream()
                    var headerBytes = 0
                    fun line(): String {
                        val bytes = java.io.ByteArrayOutputStream()
                        while (true) {
                            coroutine.ensureActive()
                            if (++headerBytes > 8192) throw IOException("Callback headers too large")
                            val next = input.read()
                            if (next < 0) throw IOException("Incomplete callback")
                            if (next == 10) break
                            bytes.write(next)
                        }
                        return bytes.toString("UTF-8").trimEnd('\r')
                    }
                    val first = line().split(' ')
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val row = line()
                        if (row.isEmpty()) break
                        val split = row.indexOf(':')
                        if (split <= 0) throw IOException("Invalid callback headers")
                        headers[row.substring(0, split).lowercase()] = row.substring(split + 1).trim()
                    }
                    fun reply(status: String, success: Boolean) {
                        val body = """{"success":$success}"""
                        peer.getOutputStream().write(
                            ("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                                "Access-Control-Allow-Origin: https://commandcode.ai\r\n" +
                                "Access-Control-Allow-Methods: POST, OPTIONS\r\n" +
                                "Access-Control-Allow-Headers: Content-Type\r\n" +
                                "Access-Control-Allow-Private-Network: true\r\n" +
                                "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body")
                                .toByteArray(StandardCharsets.UTF_8),
                        )
                    }
                    if (headers["origin"] != null && headers["origin"] != "https://commandcode.ai") {
                        reply("403 Forbidden", false)
                    } else if (first.getOrNull(1) != "/callback") {
                        reply("404 Not Found", false)
                    } else if (first.firstOrNull() == "OPTIONS") {
                        reply("200 OK", true)
                    } else if (first.firstOrNull() != "POST") {
                        reply("405 Method Not Allowed", false)
                    } else {
                        val length = headers["content-length"]?.toIntOrNull()
                        if (length == null || length !in 1..65536 || headers.containsKey("transfer-encoding")) {
                            reply("400 Bad Request", false)
                        } else {
                            val bytes = ByteArray(length)
                            var read = 0
                            while (read < length) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(bytes, read, length - read)
                                if (count < 0) throw IOException("Incomplete callback")
                                read += count
                            }
                            val key = parseKey(bytes.toString(StandardCharsets.UTF_8), state)
                            reply(if (key == null) "400 Bad Request" else "200 OK", key != null)
                            if (key != null) return@withContext key
                        }
                    }
                } catch (_: IOException) {
                    // Malformed or stalled local requests must not consume the login session.
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Callback closed")
    }

    override fun close() = socket.close()

    companion object {
        fun open(): CommandCodeCallbackServer {
            val nonce = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val state = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
            fun bind(port: Int) = ServerSocket().apply {
                try { bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)) }
                catch (error: IOException) { close(); throw error }
                soTimeout = 250
            }
            val socket = try { bind(5959) } catch (_: java.net.BindException) { bind(0) }
            return CommandCodeCallbackServer(socket, state)
        }

        fun parseKey(body: String, expectedState: String): String? = try {
            val json = JSONObject(body)
            if (json.opt("state") != expectedState ||
                listOf("apiKey", "userId", "userName", "keyName").any {
                    (json.opt(it) as? String).isNullOrBlank()
                }) null else json.getString("apiKey")
        } catch (_: org.json.JSONException) { null }
    }
}
