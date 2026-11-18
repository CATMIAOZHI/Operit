package com.ai.assistance.operit.api.chat.llmprovider

import android.app.Application
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.stream.TextStreamEventCarrier
import com.ai.assistance.operit.util.stream.TextStreamEventType
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A stream that drops halfway is rolled back and sent again; the output count then holds only
 * what the retry produced. Robolectric, because the retry waits and resumes on another thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OpenAIRetryOutputCountTest {

    @org.junit.After
    fun clearRejections() = StreamUsageOptionRejections.clearForTest()

    @Test
    fun `a refusal is remembered when an ordinary retry with the same key gets through`() = runBlocking {
        assertEquals(true, refusalRemembered(SingleApiKeyProvider("k"), "https://same.test/v1/chat/completions"))
    }

    @Test
    fun `a refusal is not remembered when the ordinary retry used another key`() = runBlocking {
        var taken = 0
        val rotating = object : ApiKeyProvider {
            override suspend fun getApiKey(): String = "key-${++taken}"
            override suspend fun getCandidateKeyCount(): Int = 2
        }
        assertEquals(false, refusalRemembered(rotating, "https://other.test/v1/chat/completions"))
    }

    /** 400 naming the field, then a 503 for the request without it, then success. */
    private suspend fun refusalRemembered(keys: ApiKeyProvider, endpoint: String): Boolean {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            when (calls) {
                1 -> builder.code(400).message("Bad Request").body(
                    """{"error":{"message":"Unrecognized request argument supplied: stream_options"}}"""
                        .toResponseBody("application/json".toMediaType()))
                2 -> builder.code(503).message("Unavailable").body("{}".toResponseBody("application/json".toMediaType()))
                else -> builder.code(200).message("OK").body(
                    ("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n").toResponseBody("text/event-stream".toMediaType()))
            }.build()
        }.build()
        val provider = OpenAIProvider(endpoint, keys, "m", client, providerType = ApiProviderType.OPENAI_GENERIC)
        provider.sendMessage(
            context = RuntimeEnvironment.getApplication(),
            chatHistory = listOf(PromptTurn(PromptTurnKind.USER, "hello")),
            modelParameters = emptyList(), enableThinking = false, stream = true,
            availableTools = null, preserveThinkInHistory = false,
            onTokensUpdated = { _, _, _ -> }, onUsageReported = null, onNonFatalError = {},
            enableRetry = true, statsCategory = null,
        ).collect { }
        assertEquals(3, calls)
        return StreamUsageOptionRejections.isRejected(endpoint, "m")
    }

    @Test
    fun `a retried attempt counts only its own output`() = runBlocking {
        var calls = 0
        val retryText = "the retried answer arrives in full"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val body = if (calls == 1) droppingStream() else
                ("data: {\"choices\":[{\"delta\":{\"content\":\"$retryText\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n").toResponseBody("text/event-stream".toMediaType())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body).build()
        }.build()
        val provider = OpenAIProvider(
            "https://drops.test/v1/chat/completions", SingleApiKeyProvider("k"), "m", client,
            providerType = ApiProviderType.OPENAI_GENERIC,
        )
        val response = provider.sendMessage(
            context = RuntimeEnvironment.getApplication(),
            chatHistory = listOf(PromptTurn(PromptTurnKind.USER, "hello")),
            modelParameters = emptyList(), enableThinking = false, stream = true,
            availableTools = null, preserveThinkInHistory = false,
            onTokensUpdated = { _, _, _ -> },
            onUsageReported = null,
            onNonFatalError = {},
            enableRetry = true,
            statsCategory = null,
        )
        val text = StringBuilder()
        response.collect { text.append(it) }
        assertEquals(2, calls)
        assertTrue("the first attempt streamed before it dropped", text.startsWith("word "))
        assertTrue("the retry was delivered", text.endsWith(retryText))
        assertTrue("the dropped attempt was rolled back",
            (response as TextStreamEventCarrier).eventChannel.replayCache.any { it.eventType == TextStreamEventType.ROLLBACK })
        val expected = ChatUtils.estimateTokenCount(retryText)
        assertTrue(expected > 0)
        assertEquals(expected, provider.outputTokenCount)
    }

    /** Some content, then the connection resets. */
    private fun droppingStream(): ResponseBody {
        val source = object : okio.Source {
            private var sent = false
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                if (sent) throw java.net.SocketException("Connection reset")
                sent = true
                val chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"" + "word ".repeat(200) +
                    "\"},\"finish_reason\":null}]}\n\n"
                sink.writeUtf8(chunk)
                return chunk.length.toLong()
            }
            override fun timeout() = okio.Timeout.NONE
            override fun close() {}
        }.buffer()
        return object : ResponseBody() {
            override fun contentType() = "text/event-stream".toMediaType()
            override fun contentLength() = -1L
            override fun source() = source
        }
    }
}
