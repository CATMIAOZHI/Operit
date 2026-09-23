package com.ai.assistance.operit.data.api

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexDeviceCodeLoginTest {
    @Test
    fun pendingAuthorizationThenExchangeUsesDeviceCallback() = runBlocking {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var polls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths += request.url.encodedPath
            bodies += okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val (status, body) = when (request.url.encodedPath) {
                "/api/accounts/deviceauth/usercode" ->
                    200 to """{"device_auth_id":"device-secret","user_code":"ABCD-EFGH","interval":"5"}"""
                "/api/accounts/deviceauth/token" -> {
                    polls++
                    if (polls == 1) 403 to """{"error":"authorization_pending"}"""
                    else 200 to """{"authorization_code":"issued-code","code_verifier":"verifier"}"""
                }
                "/oauth/token" ->
                    200 to """{"id_token":"id","access_token":"access","refresh_token":"refresh","expires_in":3600}"""
                else -> 500 to "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody()).build()
        }.build()
        val client = CodexOAuthClient(http, "https://auth.example.test")

        val device = client.requestDeviceCode()
        assertEquals("ABCD-EFGH", device.userCode)
        assertEquals(5L, device.intervalSeconds)
        assertEquals("https://auth.example.test/codex/device", device.verificationUrl)
        assertNull(client.pollDeviceCode(device))
        assertEquals("access", client.pollDeviceCode(device)?.accessToken)
        assertEquals(
            listOf(
                "/api/accounts/deviceauth/usercode",
                "/api/accounts/deviceauth/token",
                "/api/accounts/deviceauth/token",
                "/oauth/token",
            ),
            paths,
        )
        assertTrue(bodies[0].contains(CodexOAuthProtocol.CLIENT_ID))
        assertTrue(bodies[1].contains("device-secret"))
        assertTrue(bodies[3].contains("redirect_uri=https%3A%2F%2Fauth.example.test%2Fdeviceauth%2Fcallback"))
        assertTrue(bodies[3].contains("code_verifier=verifier"))
    }

    @Test
    fun unavailableDeviceAuthorizationHasActionableError() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(404).message("test").body("{}".toResponseBody()).build()
        }.build()
        try {
            CodexOAuthClient(http, "https://auth.example.test").requestDeviceCode()
            throw AssertionError("Expected requestDeviceCode to fail")
        } catch (error: IOException) {
            assertTrue(error is CodexDeviceCodeDisabledException)
        }
    }

    @Test
    fun cancellingDeviceLoginCancelsTheHttpCall() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(Unit)
            while (!chain.call().isCanceled()) {
                Thread.sleep(10)
            }
            cancelled.set(true)
            throw IOException("Canceled")
        }.build()
        val job = launch {
            CodexOAuthClient(http, "https://auth.example.test").requestDeviceCode()
        }
        withTimeout(2_000) { started.await() }
        job.cancelAndJoin()
        withTimeout(2_000) {
            while (!cancelled.get()) delay(10)
        }
        assertTrue(cancelled.get())
    }
}
