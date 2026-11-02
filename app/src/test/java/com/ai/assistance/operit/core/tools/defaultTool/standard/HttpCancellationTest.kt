package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class HttpCancellationTest {
    @Test
    fun cancellingClosesTheSocketWhileWaitingForHeadersOrBody() = runBlocking {
        for (mode in listOf("ordinary", "streaming", "multipart")) {
            for (sendHeaders in listOf(false, true)) {
                val arrived = CompletableDeferred<Unit>()
                val disconnected = CompletableDeferred<Unit>()
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 5000
                    val peer = thread(isDaemon = true) {
                        try {
                            server.accept().use { socket ->
                                socket.soTimeout = 5000
                                val input = socket.getInputStream().bufferedReader()
                                var contentLength = 0
                                while (true) {
                                    val header = input.readLine()
                                    if (header.isNullOrEmpty()) break
                                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                                        contentLength = header.substringAfter(':').trim().toInt()
                                    }
                                }
                                repeat(contentLength) { check(input.read() != -1) }
                                if (sendHeaders) {
                                    socket.getOutputStream().apply {
                                        write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nx".toByteArray())
                                        flush()
                                    }
                                }
                                arrived.complete(Unit)
                                check(input.read() == -1)
                                disconnected.complete(Unit)
                            }
                        } catch (error: Exception) {
                            arrived.completeExceptionally(error)
                            disconnected.completeExceptionally(error)
                        }
                    }
                    val tools = StandardHttpTools(Mockito.mock(Context::class.java))
                    val tool = AITool(
                        name = if (mode == "multipart") "multipart_request" else "http_request",
                        parameters = listOf(
                            ToolParameter("url", "http://127.0.0.1:${server.localPort}/"),
                            ToolParameter("form_data", "{\"test\":\"value\"}")
                        )
                    )
                    val request = launch(Dispatchers.IO) {
                        when (mode) {
                            "streaming" -> tools.httpRequestStream(tool).collect()
                            // Match the JS bridge and the blocking ToolRegistration entry.
                            else -> runInterruptible(Dispatchers.IO) {
                                runBlocking(Dispatchers.IO) {
                                    if (mode == "multipart") tools.multipartRequest(tool)
                                    else tools.httpRequest(tool)
                                }
                            }
                        }
                    }
                    try {
                        withTimeout(5000) { arrived.await() }
                        withTimeout(2000) {
                            request.cancelAndJoin()
                            disconnected.await()
                        }
                        assertTrue(request.isCancelled)
                    } finally {
                        request.cancel()
                        peer.join(1000)
                    }
                }
            }
        }
    }
}
