package com.ai.assistance.operit.api.chat.llmprovider

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.content.Context
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.StreamLogger
import com.ai.assistance.operit.util.stream.TextStreamEventCarrier
import com.ai.assistance.operit.util.stream.TextStreamEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class OpenAIStreamUsageOptionTest {

    @After
    fun clearRejections() = StreamUsageOptionRejections.clearForTest()

    @Test
    fun `streamed chat completions ask for usage, generic endpoints included`() {
        val body = JSONObject()
        body.applyChatCompletionsStreamUsageOption(stream = true, useResponsesApi = false)
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    @Test
    fun `responses, non streaming and suppressed requests omit stream options`() {
        val responsesBody = JSONObject()
        responsesBody.applyChatCompletionsStreamUsageOption(stream = true, useResponsesApi = true)
        assertFalse(responsesBody.has("stream_options"))

        val nonStreamingBody = JSONObject()
        nonStreamingBody.applyChatCompletionsStreamUsageOption(stream = false, useResponsesApi = false)
        assertFalse(nonStreamingBody.has("stream_options"))

        val suppressedBody = JSONObject()
        suppressedBody.applyChatCompletionsStreamUsageOption(stream = true, useResponsesApi = false, suppressed = true)
        assertFalse(suppressedBody.has("stream_options"))
    }

    @Test
    fun `only a 400 or 422 naming the option counts as a refusal`() {
        val body = """{"error":{"message":"Unrecognized request argument supplied: stream_options"}}"""
        assertTrue(isStreamUsageOptionRejection(400, body))
        assertTrue(isStreamUsageOptionRejection(422, """{"detail":"extra field include_usage not permitted"}"""))
        assertFalse(isStreamUsageOptionRejection(500, body))
        assertFalse(isStreamUsageOptionRejection(429, body))
        assertFalse(isStreamUsageOptionRejection(400, """{"error":"model not found"}"""))
        // The field only echoed back with the request is not a refusal of it.
        assertFalse(isStreamUsageOptionRejection(
            422, """{"detail":[{"loc":["body","messages"],"msg":"field required","input":{"stream_options":{}}}]}"""))
        assertFalse(isStreamUsageOptionRejection(
            400, """{"error":"model not found","request":{"stream_options":{"include_usage":true}}}"""))
        assertTrue(isStreamUsageOptionRejection(
            422, """{"detail":[{"loc":["body","stream_options"],"msg":"Extra inputs are not permitted"}]}"""))
        assertFalse(isStreamUsageOptionRejection(
            422, """[{"msg":"invalid model","input":{"stream_options":{"include_usage":true}}}]"""))
        assertTrue(isStreamUsageOptionRejection(400, "[400] unknown argument stream_options"))
        assertTrue(isStreamUsageOptionRejection(400, """{"error":{"param":"stream\u005foptions","message":"Unknown field"}}"""))
        // A bad escape falls back to reading the body as text instead of throwing.
        assertTrue(isStreamUsageOptionRejection(400, """{"other":"\uZZZZ","error":"unknown stream_options"}"""))
        // Deep nesting is skipped without recursing through it.
        val deep = "[".repeat(20_000) + "]".repeat(20_000)
        assertFalse(isStreamUsageOptionRejection(400, """{"error":"bad","x":$deep,"input":{"stream_options":1}}"""))
        val deepNamed = "[".repeat(40) + "\"unknown field stream_options\"" + "]".repeat(40)
        assertTrue(isStreamUsageOptionRejection(400, """{"error":$deepNamed}"""))
        // `body` grouping the diagnosis counts; `body` carrying the request back does not.
        assertTrue(isStreamUsageOptionRejection(422, """{"errors":{"body":{"stream_options":["Unknown field"]}}}"""))
        assertFalse(isStreamUsageOptionRejection(
            422, """{"error":"bad model","body":{"stream_options":{"include_usage":true},"model":"m","messages":[{"role":"user","content":"x"}]}}"""))
        assertTrue(isStreamUsageOptionRejection(
            422, """{"error":"unknown include_usage","body":{"model":"m","messages":[]}}"""))
        // Another field that only starts with the name is not this one.
        assertFalse(isStreamUsageOptionRejection(400, """{"error":{"param":"stream_options_extra","message":"Unknown field"}}"""))
        assertTrue(isStreamUsageOptionRejection(400, """{"error":{"param":"body.stream_options.include_usage"}}"""))
    }

    @Test
    fun `usage without an output count keeps the local output estimate`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(("data: {\"choices\":[{\"delta\":{\"content\":\"a fairly long answer here\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":null}}\n\n" +
                    "data: [DONE]\n\n").toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val provider = OpenAIProvider(
            "https://partial.test/v1/chat/completions", SingleApiKeyProvider("k"), "m", client,
            providerType = ApiProviderType.OPENAI_GENERIC,
        )
        quietly { send(provider) }
        assertEquals(100, provider.inputTokenCount)
        assertTrue("the estimate is kept, not set to 0", provider.outputTokenCount > 0)
    }

    @Test
    fun `a generic endpoint sends include_usage on the wire`() = runBlocking {
        var sent: JSONObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer().also { chain.request().body!!.writeTo(it) }
            sent = JSONObject(buffer.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(500).message("x")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val provider = OpenAIProvider(
            "https://wire.test/v1/chat/completions", SingleApiKeyProvider("k"), "m", client,
            providerType = ApiProviderType.OPENAI_GENERIC,
        )
        quietly { runCatching { send(provider) } }
        assertEquals(true, sent?.optJSONObject("stream_options")?.optBoolean("include_usage"))
    }

    @Test
    fun `the real grok account body keeps include_usage and command code leaves it out`() = runBlocking {
        val manager = Mockito.mock(com.ai.assistance.operit.data.api.ProviderAccountManager::class.java)
        val grok = GrokAccountProvider(manager, "grok-4.6", OkHttpClient(), emptyMap(), vision = false, tools = false)
        val grokBody = quietly { builtBody(grok) }
        assertEquals(true, grokBody.optJSONObject("stream_options")?.optBoolean("include_usage"))

        val commandCode = CommandCodeProvider(manager, "deepseek/deepseek-v4-flash", OkHttpClient(), emptyMap(), vision = false, tools = false)
        val commandCodeBody = quietly { builtBody(commandCode) }
        assertFalse(commandCodeBody.has("stream_options"))
        // What actually goes to Command Code after its transport rewrites the body.
        val compiled = CommandCodeTransport.compile(commandCodeBody, "")
        assertFalse(compiled.toString().contains("stream_options"))
    }

    /** The body the provider class itself builds for a streamed request (overrides included). */
    private fun builtBody(provider: OpenAIProvider): JSONObject {
        val method = OpenAIProvider::class.java.getDeclaredMethod(
            "createRequestBody", Context::class.java, List::class.java, List::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, List::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val body = method.invoke(
            provider, Mockito.mock(Context::class.java), listOf(PromptTurn(PromptTurnKind.USER, "hello")),
            emptyList<Any>(), false, true, null, false,
        ) as okhttp3.RequestBody
        return JSONObject(okio.Buffer().also { body.writeTo(it) }.readUtf8())
    }

    @Test
    fun `a cached count only in input_tokens_details is used`() {
        val usage = OpenAIResponsesPayloadAdapter.parseUsageCounts(JSONObject(
            """{"prompt_tokens":100,"completion_tokens":5,"prompt_tokens_details":{},"input_tokens_details":{"cached_tokens":80}}"""
        ))!!
        assertTrue(usage.cacheKnown)
        assertEquals(80, usage.cachedInputTokens)
        assertEquals(20, usage.actualInputTokens)
    }

    @Test
    fun `a null hit count still counts as usage seen, as before`() {
        val partial = OpenAIResponsesPayloadAdapter.parseUsageCounts(JSONObject(
            """{"input_tokens_details":{"cached_tokens":null,"cache_creation_input_tokens":45}}"""
        ))!!
        assertFalse(partial.cacheKnown)
    }

    @Test
    fun `output flushed after the reported usage is not estimated again`() = runBlocking {
        val calls = """[{"index":0,"id":"call-a","type":"function","function":{"name":"use_package","arguments":"{\"package_name\":\"super_admin\"}"}}]"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(("data: {\"choices\":[{\"delta\":{\"tool_calls\":$calls},\"finish_reason\":null}]}\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5}}\n\n" +
                    "data: [DONE]\n\n").toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val provider = OpenAIProvider(
            apiEndpoint = "https://tools.test/v1/chat/completions",
            apiKeyProvider = SingleApiKeyProvider("k"),
            modelName = "m",
            client = client,
            providerType = ApiProviderType.OPENAI_GENERIC,
            enableToolCall = true,
        )
        val result = quietly { send(provider) }
        assertTrue("the tool call is still delivered", result.text.contains("use_package"))
        assertEquals(5, provider.outputTokenCount)
    }

    @Test
    fun `the least recently used refusal is dropped first`() {
        StreamUsageOptionRejections.markRejected("https://e.test", "hot")
        for (i in 0 until 63) StreamUsageOptionRejections.markRejected("https://e.test", "m$i")
        assertTrue(StreamUsageOptionRejections.isRejected("https://e.test", "hot"))
        StreamUsageOptionRejections.markRejected("https://e.test", "new")
        assertTrue(StreamUsageOptionRejections.isRejected("https://e.test", "hot"))
        assertTrue(StreamUsageOptionRejections.isRejected("https://e.test", "new"))
        assertFalse(StreamUsageOptionRejections.isRejected("https://e.test", "m0"))
    }

    @Test
    fun `a refusal is remembered per endpoint and model`() {
        val endpoint = "https://example.com/v1/chat/completions"
        assertFalse(StreamUsageOptionRejections.isRejected(endpoint, "a"))
        StreamUsageOptionRejections.markRejected(" $endpoint ", "a")
        assertTrue(StreamUsageOptionRejections.isRejected(endpoint, "a"))
        assertFalse(StreamUsageOptionRejections.isRejected(endpoint, "b"))
    }

    @Test
    fun `the retry body loses only the option`() {
        val original = JSONObject()
            .put("model", "m")
            .put("stream", true)
            .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
        original.applyChatCompletionsStreamUsageOption(stream = true, useResponsesApi = false)
        val body = original.toString().toRequestBody("application/json".toMediaType())

        val retried = requestBodyWithoutStreamUsageOption(body)!!
        val buffer = okio.Buffer().also { retried.writeTo(it) }
        val json = JSONObject(buffer.readUtf8())
        assertFalse(json.has("stream_options"))
        assertEquals("m", json.getString("model"))
        assertEquals("hi", json.getJSONArray("messages").getJSONObject(0).getString("content"))

        assertNull(requestBodyWithoutStreamUsageOption(retried))
        assertNull(requestBodyWithoutStreamUsageOption("not json".toRequestBody(null)))
    }

    @Test
    fun `the member is cut wherever it sits and nested lookalikes stay`() {
        fun cut(json: String): String {
            val bytes = json.toByteArray()
            val range = topLevelMemberRange(bytes, "stream_options")!!
            return String(bytes, 0, range.first) + String(bytes, range.last + 1, bytes.size - range.last - 1)
        }
        assertEquals("""{"a":1}""", cut("""{"stream_options":{"include_usage":true},"a":1}"""))
        assertEquals("""{"a":1}""", cut("""{"a":1,"stream_options":{"include_usage":true}}"""))
        assertEquals("""{}""", cut("""{"stream_options":{"include_usage":true}}"""))
        val nested = """{"t":{"stream_options":1},"s":"\"stream_options\":","stream_options":{"x":[1,{"y":"}"}]},"z":2}"""
        assertEquals("""{"t":{"stream_options":1},"s":"\"stream_options\":","z":2}""", cut(nested))
        JSONObject(cut(nested))
        assertNull(topLevelMemberRange("""{"t":{"stream_options":1}}""".toByteArray(), "stream_options"))
    }

    @Test
    fun `a named refusal is retried once without the field, same key, no rollback, then remembered`() = runBlocking {
        val sent = mutableListOf<Pair<JSONObject, String?>>()
        var keysTaken = 0
        val rotatingKeys = object : ApiKeyProvider {
            override suspend fun getApiKey(): String = "key-${++keysTaken}"
            override suspend fun getCandidateKeyCount(): Int = 2
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer().also { chain.request().body!!.writeTo(it) }
            val json = JSONObject(buffer.readUtf8())
            sent += json to chain.request().header("Authorization")
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            if (json.has("stream_options")) {
                builder.code(400).message("Bad Request").body(
                    """{"error":{"message":"Unrecognized request argument supplied: stream_options"}}"""
                        .toResponseBody("application/json".toMediaType())
                ).build()
            } else {
                builder.code(200).message("OK").body(
                    ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n").toResponseBody("text/event-stream".toMediaType())
                ).build()
            }
        }.build()
        val provider = OpenAIProvider(
            "https://refuses.test/v1/chat/completions",
            rotatingKeys,
            "m", client, providerType = ApiProviderType.OPENAI_GENERIC,
        )
        quietly {
            val first = send(provider)
            assertEquals("ok", first.text)
            assertFalse("no rollback for a refusal before the stream", first.rolledBack)
            assertTrue("no notice for the silent retry", first.notices.isEmpty())
            assertEquals(2, sent.size)
            assertTrue(sent[0].first.has("stream_options"))
            assertFalse(sent[1].first.has("stream_options"))
            assertEquals(sent[0].second, sent[1].second)
            assertEquals("the retry keeps the key it was refused with", 1, keysTaken)
            assertTrue(StreamUsageOptionRejections.isRejected("https://refuses.test/v1/chat/completions", "m"))

            send(provider)
            assertEquals(3, sent.size)
            assertFalse(sent[2].first.has("stream_options"))
        }
    }

    @Test
    fun `an empty stream after the refusal is not remembered`() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val buffer = okio.Buffer().also { chain.request().body!!.writeTo(it) }
            val refused = JSONObject(buffer.readUtf8()).has("stream_options")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (refused) 422 else 200).message("x")
                .body((if (refused) """{"detail":"include_usage not permitted"}""" else "data: [DONE]\n\n")
                    .toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val provider = OpenAIProvider(
            "https://empty.test/v1/chat/completions", SingleApiKeyProvider("k"), "m", client,
            providerType = ApiProviderType.OPENAI_GENERIC,
        )
        quietly { runCatching { send(provider) } }
        assertEquals(2, calls)
        assertFalse(StreamUsageOptionRejections.isRejected("https://empty.test/v1/chat/completions", "m"))
    }

    @Test
    fun `a generic endpoint reports the usage chunk sent after the content`() = runBlocking {
        var sentOption = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer().also { chain.request().body!!.writeTo(it) }
            sentOption = JSONObject(buffer.readUtf8()).optJSONObject("stream_options")?.optBoolean("include_usage") == true
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5," +
                    "\"prompt_tokens_details\":{\"cached_tokens\":80}}}\n\n" +
                    "data: [DONE]\n\n").toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val provider = OpenAIProvider(
            "https://ollama.test/v1/chat/completions", SingleApiKeyProvider("k"), "m", client,
            providerType = ApiProviderType.OPENAI_GENERIC,
        )
        val result = quietly { send(provider) }
        assertTrue(sentOption)
        assertEquals(100, provider.inputTokenCount)
        assertEquals(80, provider.cachedInputTokenCount)
        assertEquals(5, provider.outputTokenCount)
        val usage = result.usage.last()
        assertEquals(100L, usage.totalInputTokens)
        assertEquals(80L, usage.cachedInputTokens)
        assertEquals(20L, usage.uncachedInputTokens)
        assertEquals(5L, usage.outputTokens)
    }

    private class SendResult(
        val text: String,
        val rolledBack: Boolean,
        val notices: List<String>,
        val usage: List<com.ai.assistance.operit.data.stats.ProviderUsageSnapshot>,
    )

    private suspend fun send(provider: OpenAIProvider): SendResult {
        val notices = mutableListOf<String>()
        val usage = mutableListOf<com.ai.assistance.operit.data.stats.ProviderUsageSnapshot>()
        val response = provider.sendMessage(
            context = Mockito.mock(Context::class.java),
            chatHistory = listOf(PromptTurn(PromptTurnKind.USER, "hello")),
            modelParameters = emptyList(), enableThinking = false, stream = true,
            availableTools = null, preserveThinkInHistory = false,
            onTokensUpdated = { _, _, _ -> },
            onUsageReported = { snapshot, _ -> usage += snapshot },
            onNonFatalError = { notices += it },
            enableRetry = false,
            statsCategory = null,
        )
        val text = StringBuilder()
        response.collect { text.append(it) }
        val rolledBack = (response as TextStreamEventCarrier).eventChannel.replayCache
            .any { it.eventType == TextStreamEventType.ROLLBACK }
        return SendResult(text.toString(), rolledBack, notices, usage)
    }

    private suspend fun <T> quietly(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            Mockito.mockStatic(AppLogger::class.java).use {
                try {
                    StreamLogger.setEnabled(false)
                    block()
                } finally {
                    StreamLogger.setEnabled(true)
                }
            }
        }
}
