package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.StreamLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class GeminiInteractionsTest {
    private fun user(text: String) = PromptTurn(PromptTurnKind.USER, text)
    private fun assistant(text: String) = PromptTurn(PromptTurnKind.ASSISTANT, text)

    @Test fun onlyAnUnmodifiedPrefixInTheSameScopeCanContinue() {
        val initial = listOf(user("first"), assistant("earlier"), user("photo"))
        val checkpoint = InteractionHistory(initial, "chat/model").checkpoint("answer", "id", JSONArray())
        val history = initial + assistant("answer") + user("more")
        fun parent(turns: List<PromptTurn>, scope: String = "chat/model") = InteractionHistory(turns, scope).parent(checkpoint)
        assertEquals(listOf(user("more")), parent(history)!!.suffix)
        assertNull(parent(history.drop(1)))
        assertNull(parent(listOf(user("different")) + history.drop(1)))
        assertNull(parent(initial + assistant("edited") + user("more")))
        assertNull(parent(history, scope = "another account"))
        assertNull(parent(history, scope = "chat/otherModel"))
        assertNull(parent(history, scope = "otherChat/model"))
    }

    @Test fun reloadedCopiesReuseDigestsButAnEditOfTheSameLengthDoesNot() {
        val initial = listOf(user("first"), assistant("Aa"), user("photo"))
        val checkpoint = InteractionHistory(initial, "reload").checkpoint("answer", "id", JSONArray())
        fun copy(text: String) = String(text.toCharArray())
        val reloaded = (initial + assistant("answer") + user("more")).map { it.copy(content = copy(it.content)) }
        assertEquals("id", InteractionHistory(reloaded, "reload").parent(checkpoint)?.id)
        // Same length and same String.hashCode as "Aa".
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val edited = reloaded.toMutableList().also { it[1] = assistant("BB") }
        assertNull(InteractionHistory(edited, "reload").parent(checkpoint))
    }

    @Test fun toolMediaStaysWithItsCallAndOptionsAreResent() {
        val source = JSONObject("""{
            "contents":[{"role":"user","parts":[{"functionResponse":{"id":"fc2","name":"read",
                "response":{"result":"listen"},"parts":[{"inlineData":{"mimeType":"audio/wav","data":"AQID"}}]}}]}],
            "tools":[{"function_declarations":[{"name":"read","parameters":{"type":"object"}}]}],
            "systemInstruction":{"parts":[{"text":"be concise"}]},
            "generationConfig":{"maxOutputTokens":40,"thinkingConfig":{"includeThoughts":true,"thinkingLevel":"HIGH"}}
        }""")
        val body = interactionOptions(source, "models/gemini-3", true, true)
        addInteractionInput(body, source, "parent")
        assertEquals("parent", body.getString("previous_interaction_id"))
        assertEquals("gemini-3", body.getString("model"))
        assertTrue(body.getBoolean("store"))
        assertEquals("be concise", body.getString("system_instruction"))
        assertEquals("high", body.getJSONObject("generation_config").getString("thinking_level"))
        assertEquals("function", body.getJSONArray("tools").getJSONObject(0).getString("type"))
        val result = body.getJSONArray("input").getJSONObject(0)
        assertEquals("fc2", result.getString("call_id"))
        assertEquals("audio", result.getJSONArray("result").getJSONObject(1).getString("type"))
    }

    @Test fun reopenedTranscriptCanRejoinTheStoredReasoningAndToolBlocks() {
        val first = listOf(user("look"))
        val reply = """<think source="provider">thought</think>answer<tool_a name="read"></tool_a>"""
        val checkpoint = InteractionHistory(first, "chat").checkpoint(reply, "id", JSONArray())
        val reopened = reply.replace("</think>", "</think>\n").replace("<tool_a", "\n<tool_a")
        val history = first + assistant(reopened) + user("next")
        assertEquals("id", InteractionHistory(history, "chat").parent(checkpoint)?.id)
        assertNull(InteractionHistory(first + assistant(reopened.replace("thought", "changed")) +
            user("next"), "chat").parent(checkpoint))
    }

    @Test fun completedLegacyHistoryBootstrapsWithoutReplayingCallsOrLosingMedia() {
        val source = JSONObject("""{"contents":[
            {"role":"model","parts":[{"thought":true,"text":"internal","thoughtSignature":"old"},
              {"text":"looked up","thoughtSignature":"old"},
              {"functionCall":{"name":"read","args":{"file":"a"}},"thoughtSignature":"old"}]},
            {"role":"user","parts":[{"functionResponse":{"name":"read","response":{"result":"photo"},
                "parts":[{"inlineData":{"mimeType":"image/png","data":"AAAA"}}]}}]},
            {"role":"user","parts":[{"text":"next"}]}]}""")
        val body = JSONObject()
        addInteractionInput(body, source, null)
        val input = body.getJSONArray("input").objects()
        assertEquals(listOf("model_output", "user_input", "user_input"), input.map { it.getString("type") })
        assertTrue(input[0].toString().contains("looked up"))
        assertFalse(input[0].toString().contains("internal"))
        assertFalse(body.toString().contains("thoughtSignature"))
        assertEquals("AAAA", input[1].getJSONArray("content").getJSONObject(1).getString("data"))
    }

    @Test fun typedToolTurnsRejoinTheOriginalCheckpointAndKeepTheCallId() {
        val first = listOf(user("look"))
        val tool = """<tool_abc name="read"><param name="file">a</param></tool_abc>"""
        val calls = JSONArray().put(JSONObject().put("id", "server-id").put("name", "read").put("matchingName", "read"))
        val saved = InteractionHistory(first, "tools").checkpoint("let me look\n$tool", "turn", calls)
        val results = PromptTurn(PromptTurnKind.TOOL_RESULT, """<tool_result name="read">done</tool_result>""")
        val local = first + assistant("let me look") + PromptTurn(PromptTurnKind.TOOL_CALL, tool) + results
        val compiled = StructuredToolCallBridge.compileHistoryForProvider(local, true)
        val parent = InteractionHistory(compiled, "tools").parent(saved)!!
        assertEquals(listOf(results.content), parent.suffix.map { it.content })
        assertEquals("server-id", parent.pending.single().getString("id"))
    }

    @Test fun negativeCacheExpiresAndDistinguishesAMissingParentFromAMissingModel() {
        InteractionAvailability.block("test-unavailable", 5)
        assertTrue(InteractionAvailability.blocked("test-unavailable", 6))
        assertFalse(InteractionAvailability.blocked("test-unavailable", 3_600_005))
        assertEquals(InteractionUnavailable.Cache.MODEL, interactionRejection(404, null, false).cache)
        assertEquals(InteractionUnavailable.Cache.NONE, interactionRejection(404, null, true).cache)
        assertEquals(InteractionUnavailable.Cache.SESSION, interactionRejection(400, null, false).cache)
    }

    @Test fun unsupportedConfigurationIsIdentifiedWithoutReadingAnyHistory() {
        val source = JSONObject("""{"generationConfig":{"thinkingConfig":{"thinkingBudget":1000}}}""")
        assertTrue(runCatching { interactionOptions(source, "gemini-2.5-flash", true, true) }
            .exceptionOrNull() is InteractionUnavailable)
        val tool = JSONObject("""{"tools":[{"function_declarations":[{"name":"read","parametersJsonSchema":{}}]}],"generationConfig":{}}""")
        assertTrue(runCatching { interactionOptions(tool, "gemini-3", true, true) }
            .exceptionOrNull() is InteractionUnavailable)
    }

    @Test fun oldStreamsFallbackAndReadyMadeStartContentIsEmitted() = runBlocking {
        val frames = mutableListOf<JSONObject>()
        fun decoder() = GeminiInteractionResponse(false, "断流", "错误") { frames += it }
        for (stream in listOf(
            """data: {"candidates":[]}""", """data: {"event_type":"content.start"}""",
            """data: {"event_type":"unknown.event"}""",
        )) {
            assertTrue(runCatching { decoder().read(stream.reader(), true) }.exceptionOrNull() is InteractionUnavailable)
        }
        decoder().read("""
            data: {"event_type":"step.start","index":0,"step":{"type":"model_output","content":[{"type":"text","text":"initial"}]}}
            data: {"event_type":"step.stop","index":0}
            data: {"event_type":"interaction.completed","interaction":{"id":"id","status":"completed"}}
        """.trimIndent().reader(), true)
        assertTrue(frames.single().toString().contains("initial"))
    }

    @Test fun rejectedUsageRequiresEvidenceOfGeneration() = runBlocking {
        for (tokens in listOf(0, 8)) {
            val frames = mutableListOf<JSONObject>()
            val decoder = GeminiInteractionResponse(false, "断流", "错误") { frames += it }
            val failed = """{"status":"failed","error":{"code":"invalid_request","message":"bad"},
                "usage":{"total_input_tokens":10,"total_output_tokens":$tokens}}"""
            assertTrue(runCatching { decoder.read(failed.reader(), false) }.exceptionOrNull() is InteractionUnavailable)
            assertEquals(if (tokens == 0) 0 else 1, frames.count { it.has("usageMetadata") })
        }
    }

    @Test fun streamKeepsFunctionIdAndBillsThoughtsOnce() = runBlocking {
        val frames = mutableListOf<JSONObject>()
        val response = GeminiInteractionResponse(false, "断流", "错误") { frames += it }
        response.read("""
            data: {"event_type":"step.start","index":0,"step":{"type":"function_call","id":"c1","name":"read","arguments":{}}}
            data: {"event_type":"step.delta","index":0,"delta":{"type":"arguments_delta","arguments":"{\"path\":"}}
            data: {"event_type":"step.delta","index":0,"delta":{"type":"arguments_delta","arguments":"\"a\"}"}}
            data: {"event_type":"step.stop","index":0}
            data: {"event_type":"interaction.completed","interaction":{"id":"p","status":"requires_action","usage":{"total_input_tokens":11,"total_cached_tokens":4,"total_output_tokens":90,"total_thought_tokens":245}}}
        """.trimIndent().reader(), true)
        assertEquals("c1", response.pending.getJSONObject(0).getString("id"))
        val call = frames.first().getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall")
        assertEquals("a", call.getJSONObject("args").getString("path"))
        assertEquals("p", response.completedId)
        val usage = frames.last().getJSONObject("usageMetadata")
        assertEquals(335, usage.getInt("candidatesTokenCount"))
        assertEquals(245, usage.getInt("thoughtsTokenCount"))
        assertEquals(4, usage.getInt("cachedContentTokenCount"))
    }

    @Test fun truncatedStreamDoesNotDispatchIncompleteArgumentsOrCommitAnId() = runBlocking {
        val decoder = GeminiInteractionResponse(false, "断流", "错误") { fail("Incomplete tool call was emitted") }
        val error = runCatching {
            decoder.read("""
                data: {"event_type":"interaction.created","interaction":{"id":"not-a-checkpoint"}}
                data: {"event_type":"step.start","index":1,"step":{"type":"function_call","id":"id","name":"read"}}
                data: {"event_type":"step.delta","index":1,"delta":{"type":"arguments_delta","arguments":"{"}}
            """.trimIndent().reader(), true)
        }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertNull(decoder.completedId)
        assertEquals(0, decoder.pending.length())
    }

    @Test fun nonStreamingNeverDispatchesAnEchoedHistoricalCall() = runBlocking {
        val frames = mutableListOf<JSONObject>()
        val decoder = GeminiInteractionResponse(false, "断流", "错误") { frames += it }
        decoder.read("""{"id":"done","status":"completed","steps":[
            {"type":"model_output","content":[{"type":"text","text":"old answer"}]},
            {"type":"function_call","id":"old-call","name":"read","arguments":{}},
            {"type":"function_result","call_id":"old-call","result":[]},
            {"type":"user_input","content":[]},
            {"type":"google_search_call","arguments":{"queries":["query"]}},
            {"type":"model_output","content":[{"type":"text","text":"new answer",
              "annotations":[{"type":"url_citation","url":"https://example.com/x","title":"result"}]}]}
        ]}""".reader(), false)
        assertEquals(0, decoder.pending.length())
        val candidates = frames.map { it.getJSONArray("candidates").getJSONObject(0) }
        assertEquals("new answer", candidates[0].getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text"))
        val grounding = candidates[1].getJSONObject("groundingMetadata")
        assertEquals("query", grounding.getJSONArray("webSearchQueries").getString(0))
        assertEquals("result", grounding.getJSONArray("groundingChunks").getJSONObject(0).getJSONObject("web").getString("title"))
    }

    @Test fun fallbackIsLimitedToUnavailableRecordsAndUnsupportedProtocols() {
        assertTrue(interactionCanFallback(404, null, true))
        assertTrue(interactionCanFallback(0, JSONObject("""{"code":"not_found"}"""), true))
        assertTrue(interactionCanFallback(400, JSONObject("""{"message":"previous_interaction_id expired"}"""), true))
        val rejectedStream = "event: error\ndata: " + """{"error":{"code":"invalid_request","message":"previous_interaction_id expired"}}"""
        assertTrue(interactionCanFallback(400, interactionHttpError(rejectedStream), true))
        assertTrue(interactionCanFallback(400, JSONObject("""{"message":"Invalid tool parameters"}"""), false))
        assertFalse(interactionCanFallback(400, JSONObject("""{"message":"Invalid tool parameters"}"""), true))
        assertFalse(interactionCanFallback(401, null, true))
        assertFalse(interactionCanFallback(429, null, true))
        assertFalse(interactionCanFallback(503, null, true))
        assertFalse(officialInteractionsEndpoint("https://generativelanguage.googleapis.com.example/v1beta/models"))
        assertFalse(officialInteractionsEndpoint("https://proxy.example/v1beta/models"))
    }

    @Test fun expiredInteractionResendsFullHistoryOnceWithoutRetryNotification() = runBlocking {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<JSONObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            paths += req.url.encodedPath
            bodies += JSONObject(Buffer().also { req.body!!.writeTo(it) }.readUtf8())
            val (code, text) = when (paths.size) {
                1 -> 200 to """{"id":"xyz","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"one"}]}]}"""
                2 -> 404 to """{"error":{"code":"not_found","message":"Result not found"}}"""
                else -> 200 to """{"candidates":[{"content":{"role":"model","parts":[{"text":"fallback"}]}}]}"""
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(text.toResponseBody()).build()
        }.build()
        val provider = GeminiProvider("https://generativelanguage.googleapis.com", SingleApiKeyProvider("offline-test-key"), "gemini-3", client)
        var persisted: String? = null
        val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        Mockito.`when`(editor.putString(Mockito.anyString(), Mockito.anyString())).thenAnswer {
            persisted = it.getArgument(1); editor
        }
        val prefs = Mockito.mock(SharedPreferences::class.java)
        Mockito.`when`(prefs.edit()).thenReturn(editor)
        Mockito.`when`(prefs.getString(Mockito.anyString(), Mockito.isNull())).thenAnswer { persisted }
        val context = Mockito.mock(Context::class.java) {
            when (it.method.name) {
                "getString" -> "test"
                "getSharedPreferences" -> prefs
                else -> Mockito.RETURNS_DEFAULTS.answer(it)
            }
        }
        withContext(Dispatchers.IO + OpenCodeSessionContext("test", savesChatHistory = true)) {
            Mockito.mockStatic(AppLogger::class.java).use {
                try {
                    StreamLogger.setEnabled(false)
                    suspend fun send(history: List<PromptTurn>): String {
                        val output = StringBuilder()
                        provider.sendMessage(context, history, emptyList(), false, false, null,
                            onTokensUpdated = { _, _, _ -> }, onNonFatalError = { fail(it) }, enableRetry = false, statsCategory = TokenStatCategory.CHAT)
                            .collect { output.append(it) }
                        return output.toString()
                    }
                    val first = listOf(user("old content"))
                    val reply = send(first)
                    assertEquals("one", reply)
                    assertEquals("fallback", send(first + assistant(reply) + user("new content")).trim())
                } finally {
                    StreamLogger.setEnabled(true)
                }
            }
        }
        assertEquals(listOf("/v1beta/interactions", "/v1beta/interactions", "/v1beta/models/gemini-3:generateContent"), paths)
        assertEquals("xyz", bodies[1].getString("previous_interaction_id"))
        assertFalse(bodies[1].toString().contains("old content"))
        assertTrue(bodies[1].toString().contains("new content"))
        val full = bodies.last().getJSONArray("contents")
        assertEquals(listOf("old content", "one", "new content"), full.objects().map {
            it.getJSONArray("parts").getJSONObject(0).getString("text")
        })
    }
}
