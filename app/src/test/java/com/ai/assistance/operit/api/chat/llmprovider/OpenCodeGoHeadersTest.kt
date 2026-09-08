package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.StreamLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class OpenCodeGoHeadersTest {
    private val endpoint = "https://opencode.ai/zen/go/v1/chat/completions"

    @Test
    fun onlyOfficialGoEndpointsGetAutomaticHeaders() = runBlocking {
        val headers = OpenCodeGoHeaders()
        for (url in listOf(
            "https://example.test/zen/go/v1/chat/completions",
            "https://opencode.ai/zen/v1/chat/completions",
            "https://opencode.ai/zen/gopher/v1/chat/completions",
            "https://opencode.ai.example.test/zen/go/v1/chat/completions",
            "http://opencode.ai/zen/go/v1/chat/completions",
        )) {
            val builder = Request.Builder().url(url)
            headers.applyTo(builder)
            assertNull(builder.build().header("x-opencode-session"))
            assertNull(builder.build().header("User-Agent"))
        }
        for (url in listOf(endpoint, "https://opencode.ai/zen/go/v1/messages",
            "https://opencode.ai/zen/go/v1/responses")) {
            val builder = Request.Builder().url(url)
            headers.applyTo(builder)
            assertFalse(builder.build().header("x-opencode-session").isNullOrBlank())
            assertTrue(builder.build().header("User-Agent")!!.startsWith("Operit/"))
        }
    }

    @Test
    fun explicitCustomHeadersWinCaseInsensitively() = runBlocking {
        val builder = Request.Builder().url(endpoint)
            .header("X-OpenCode-Session", "custom-session")
            .header("user-agent", "custom-client")
        OpenCodeGoHeaders().applyTo(builder)
        assertEquals(listOf("custom-session"), builder.build().headers.values("x-opencode-session"))
        assertEquals("custom-client", builder.build().header("User-Agent"))
    }

    @Test
    fun fallbackIsStablePerProviderInstance() = runBlocking {
        suspend fun session(headers: OpenCodeGoHeaders): String? {
            val builder = Request.Builder().url(endpoint)
            headers.applyTo(builder)
            return builder.build().header("x-opencode-session")
        }
        val headers = OpenCodeGoHeaders()
        val first = session(headers)
        assertEquals(first, session(headers))
        assertNotEquals(first, session(OpenCodeGoHeaders()))
    }

    @Test
    fun parallelChatsAndNestedSubagentsRetainTheirIdentities() = runBlocking {
        val headers = OpenCodeGoHeaders()
        suspend fun session(): String? = withContext(Dispatchers.Default) {
            yield()
            val builder = Request.Builder().url(endpoint)
            headers.applyTo(builder)
            builder.build().header("x-opencode-session")
        }
        (0 until 12).map { index ->
            async(OpenCodeSessionContext("chat-$index")) {
                assertEquals("chat-$index", session())
                withContext(OpenCodeSessionContext("child-$index")) {
                    assertEquals("child-$index", session())
                }
                assertEquals("chat-$index", session())
            }
        }.awaitAll()
        Unit
    }

    @Test
    fun deepseekSendsSessionOnStreamedAndNonstreamedRequests() = runBlocking {
        withContext(Dispatchers.IO) {
            Mockito.mockStatic(AppLogger::class.java).use {
                StreamLogger.setEnabled(false)
                try {
                    val sent = mutableListOf<Request>()
                    val client = OkHttpClient.Builder().addInterceptor { chain ->
                        val request = chain.request()
                        sent.add(request)
                        val streaming = sent.size != 2
                        val body = if (streaming) {
                            """data: {"choices":[{"delta":{"content":"ok"},"finish_reason":null}]}""" +
                                "\n\n" + """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""" +
                                "\n\ndata: [DONE]\n\n"
                        } else {
                            """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
                        }
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                            .code(200).message("OK")
                            .body(body.toResponseBody(
                                (if (streaming) "text/event-stream" else "application/json").toMediaType()
                            )).build()
                    }.build()
                    val provider = DeepseekProvider(
                        endpoint, SingleApiKeyProvider("test-only-key"), "deepseek-v4-flash", client
                    )
                    val context = Mockito.mock(Context::class.java)
                    for ((sessionId, streaming) in listOf(
                        "chat-a" to true, "chat-a" to false, "chat-b" to true
                    )) {
                        withContext(OpenCodeSessionContext(sessionId)) {
                            val output = StringBuilder()
                            provider.sendMessage(
                                context = context,
                                chatHistory = listOf(PromptTurn(PromptTurnKind.USER, "hello")),
                                modelParameters = emptyList(),
                                enableThinking = false,
                                stream = streaming,
                                availableTools = null,
                                preserveThinkInHistory = false,
                                onTokensUpdated = { _, _, _ -> },
                                onNonFatalError = {},
                                enableRetry = false,
                            ).collect { output.append(it) }
                            assertTrue(output.toString().contains("ok"))
                        }
                    }
                    assertEquals(listOf("chat-a", "chat-a", "chat-b"),
                        sent.map { it.header("x-opencode-session") })
                    assertTrue(sent.all { it.header("User-Agent")!!.startsWith("Operit/") })
                } finally {
                    StreamLogger.setEnabled(true)
                }
            }
        }
    }
}
