package com.ai.assistance.operit.features.orbit

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Request
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import kotlinx.coroutines.runBlocking

class OrbitSecretFileTest {
    @get:Rule val folder = TemporaryFolder()
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun ciphertextSurvivesRestartAndDoesNotExposeCredential() {
        val file = folder.newFile("a.bin").apply { delete() }
        val key = key()
        val store = OrbitSecretFile(file) { key }
        assertNull(store.read())
        store.write("""{"bearer":"synthetic-credential"}""")
        assertFalse(file.readText().contains("synthetic-credential"))
        assertEquals("""{"bearer":"synthetic-credential"}""", OrbitSecretFile(file) { key }.read())
        val first = file.readBytes()
        store.write("""{"bearer":"synthetic-credential"}""")
        assertFalse(first.contentEquals(file.readBytes()))
    }
    @Test fun changedCiphertextKeyOrOwnerFailsWithoutOverwritingStoredData() {
        val file = folder.newFile("a.bin")
        val key = key()
        OrbitSecretFile(file) { key }.write("sensitive")
        val bytes = file.readBytes()
        assertTrue(runCatching { OrbitSecretFile(file) { key() }.read() }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
        val other = folder.newFile("b.bin").apply { writeBytes(bytes) }
        assertTrue(runCatching { OrbitSecretFile(other) { key }.read() }.isFailure)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertTrue(runCatching { OrbitSecretFile(file) { key }.read() }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun connectionTargetsAndTransportDoNotAllowCredentialRedirectOrRetry() {
        OrbitConnectorRepository.validate("demo", "notifications", "https://example.test/hook", "")
        for (url in listOf("http://example.test", "https://user:pass@example.test", "https://example.test/#fragment")) {
            assertTrue(runCatching { OrbitConnectorRepository.validate("demo", "notify", url, "") }.isFailure)
        }
        assertTrue(runCatching { OrbitConnectorRepository.validate("demo", "notify", "https://example.test", "a\r\nb") }.isFailure)
        val client = OrbitHttpConnector.client()
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(30_000, client.callTimeoutMillis)
    }

    @Test fun realTransportDoesNotFollowRedirectOrRepeatAnUncertainPost() {
        // HTTP loopback exercises transport policy; production configuration still requires HTTPS.
        for (status in listOf(307, 503, 0)) {
            ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 1000
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val requests = executor.submit<Int> {
                        var count = 0
                        while (true) {
                            val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { break }
                            socket.use {
                                it.soTimeout = 2000
                                val reader = it.getInputStream().bufferedReader()
                                while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                                count++
                                if (status != 0) {
                                    it.getOutputStream().write(("HTTP/1.1 $status Test\r\n" +
                                        "Retry-After: 0\r\nLocation: http://127.0.0.1:${server.localPort}/other\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                                }
                                // Otherwise close after accepting the request, without any response.
                            }
                        }
                        count
                    }
                    val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/hook")
                        .post(OrbitHttpConnector.requestBody("{}")).build()
                    val response = runCatching { OrbitHttpConnector.client().newCall(request).execute().use { it.code } }
                    if (status != 0) assertEquals(status, response.getOrThrow()) else assertTrue(response.isFailure)
                    assertEquals(1, requests.get(5, TimeUnit.SECONDS))
                } finally { executor.shutdownNow() }
            }
        }
    }

    @Test fun rejectedDuplicateCannotRemoveAnOriginalRequestsRevocationHandle() = runBlocking {
        val context = mock<android.content.Context>()
        whenever(context.noBackupFilesDir).thenReturn(folder.root)
        val original = mock<okhttp3.Call>()
        val duplicate = mock<okhttp3.Call>()
        OrbitConnectorRepository.register("operation", "orbit", "connection", original)
        OrbitConnectorRepository.unregister("operation", duplicate)
        OrbitConnectorRepository(context, "orbit").clear()
        verify(original).cancel()
        OrbitConnectorRepository.unregister("operation", original)
    }
}
