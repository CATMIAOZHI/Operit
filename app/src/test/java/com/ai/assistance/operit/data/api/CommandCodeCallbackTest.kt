package com.ai.assistance.operit.data.api

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import java.net.Socket
import java.net.URI
import java.net.URLDecoder

class CommandCodeCallbackTest {
    @Test fun requiresMatchingStateAndCompleteFields() {
        val body = """{"state":"expected","apiKey":"test-only","userId":"u","userName":"n","keyName":"k"}"""
        assertEquals("test-only", CommandCodeCallbackServer.parseKey(body, "expected"))
        assertNull(CommandCodeCallbackServer.parseKey(body, "other"))
        assertNull(CommandCodeCallbackServer.parseKey("""{"state":"expected","apiKey":"test-only"}""", "expected"))
        assertNull(CommandCodeCallbackServer.parseKey("[]", "expected"))
    }

    @Test fun browserPostCallbackReturnsKeyAndClosesPort() = runBlocking {
        val server = CommandCodeCallbackServer.open()
        val callback = URLDecoder.decode(URI(server.authorizationUrl).rawQuery.split('&').first().substringAfter('='), "UTF-8")
        val port = URI(callback).port
        try {
            val receiver = async { withTimeout(5000) { server.awaitKey() } }
            withContext(Dispatchers.IO) {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 5000
                    val body = """{"state":"${server.state}","apiKey":"test-only","userId":"u","userName":"n","keyName":"k"}"""
                    socket.getOutputStream().write(("POST /callback HTTP/1.1\r\nOrigin: https://commandcode.ai\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray())
                    assertTrue(socket.getInputStream().bufferedReader().readText().contains("200 OK"))
                }
            }
            assertEquals("test-only", receiver.await())
        } finally { server.close() }
        assertTrue(runCatching { Socket("127.0.0.1", port).use {} }.isFailure)
    }
}
