package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.collects.ApiProviderConfigs
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.OfficialModelCapabilitiesCatalog
import com.ai.assistance.operit.data.model.ModelProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeZenFreeTest {
    @Test
    fun freeProviderDoesNotRequireKeyAndRejectsPaidModelNames() {
        assertFalse(ApiProviderConfigs.requiresApiKey(ApiProviderType.OPENCODE_ZEN_FREE))
        assertEquals(OpenCodeZenFree.CHAT_ENDPOINT,
            ApiProviderConfigs.getDefaultApiEndpoint(ApiProviderType.OPENCODE_ZEN_FREE))
        assertTrue(OpenCodeZenFree.isFreeModel("mimo-v2.5-free"))
        assertTrue(OpenCodeZenFree.isFreeModel("big-pickle"))
        assertFalse(OpenCodeZenFree.isFreeModel("gpt-6-astra"))
        assertTrue(OpenCodeZenFree.isFreeModel("muse-spark-1.3-contributor-free"))
        assertTrue(OpenCodeZenFree.isResponsesModel("muse-spark-1.3-contributor-free"))
        assertFalse(OpenCodeZenFree.isResponsesModel("mimo-v2.5-free"))
        assertTrue(OpenCodeZenFree.usesResponses("future-free", ModelProtocol.RESPONSES))
        assertFalse(OpenCodeZenFree.usesResponses("muse-spark-future-free", ModelProtocol.CHAT_COMPLETIONS))
        assertTrue(OpenCodeZenFree.usesResponses("muse-spark-1.3-contributor-free", ModelProtocol.INHERIT))
    }

    @Test
    fun requestIdentityKeepsTheSameSessionAcrossTurns() = runBlocking {
        val headers = OpenCodeZenFreeHeaders()
        val first = Request.Builder().url(OpenCodeZenFree.CHAT_ENDPOINT)
        headers.applyTo(first, "request-1")
        val second = Request.Builder().url(OpenCodeZenFree.CHAT_ENDPOINT)
        headers.applyTo(second, "request-2")
        val firstRequest = first.build()
        val secondRequest = second.build()

        assertTrue(firstRequest.header("x-opencode-session").orEmpty()
            .matches(Regex("ses_[0-9a-f]{12}[0-9A-Za-z]{14}")))
        assertEquals(firstRequest.header("x-opencode-session"),
            secondRequest.header("x-opencode-session"))
        assertEquals(firstRequest.header("x-opencode-session"), firstRequest.header("x-session-affinity"))
        assertEquals(firstRequest.header("x-opencode-session"), firstRequest.header("X-Session-Id"))
        assertTrue(firstRequest.header("x-opencode-request").orEmpty().startsWith("req_"))
        assertTrue(secondRequest.header("x-opencode-request").orEmpty().startsWith("req_"))
        assertTrue(firstRequest.header("x-opencode-request") != secondRequest.header("x-opencode-request"))
        assertTrue(firstRequest.header("x-opencode-project").orEmpty().startsWith("prj_"))
        assertEquals("cli", firstRequest.header("x-opencode-client"))
    }

    @Test
    fun anonymousStreamHasGateToolsWithoutMakingThemCallable() {
        val request = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
            .put("stream", true)
        OpenCodeZenFree.ensureAnonymousRequestShape(request)
        val tools = request.getJSONArray("tools")
        assertEquals(listOf("bash", "read"), (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        })
        assertEquals("none", request.getString("tool_choice"))
    }

    @Test
    fun emptyToolsAlsoDisableInjectedTools() {
        val request = JSONObject().put("messages", JSONArray()).put("stream", false)
            .put("tools", JSONArray())
        OpenCodeZenFree.ensureAnonymousRequestShape(request)
        assertTrue(request.getBoolean("stream"))
        assertEquals("none", request.getString("tool_choice"))
    }

    @Test
    fun museResponsesUseFlatToolsAndOmitUnsupportedNoneValues() {
        val request = JSONObject()
            .put("model", "muse-spark-1.3-contributor-free")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
            .put("stream", false)
            .put("tool_choice", "none")
            .put("reasoning_effort", "none")
        val responsesRequest = OpenAIResponsesPayloadAdapter.toResponsesRequest(request)
        OpenCodeZenFree.ensureAnonymousRequestShape(responsesRequest)
        val tools = responsesRequest.getJSONArray("tools")
        assertTrue(responsesRequest.getBoolean("stream"))
        assertTrue(responsesRequest.has("input"))
        assertEquals(listOf("bash", "read"), (0 until tools.length()).map {
            tools.getJSONObject(it).getString("name")
        })
        assertFalse(tools.getJSONObject(0).has("function"))
        assertFalse(responsesRequest.has("tool_choice"))
        assertFalse(responsesRequest.has("reasoning"))
    }

    @Test
    fun muse13HasCapabilitiesWhenInstalledCatalogIsOlder() {
        val olderCatalog = OfficialModelCapabilitiesCatalog.parse(
            """{"meta/muse-spark-1.2":{"name":"Muse Spark 1.2","modalities":{"input":["text","image"]}}}"""
        )
        val matches = OpenCodeZenFree.matchMultimodalCapabilities(
            olderCatalog,
            listOf("muse-spark-1.3-contributor-free"),
        )
        assertTrue(matches.getValue("muse-spark-1.3-contributor-free").image)
    }

    @Test
    fun finalRequestCannotUseAParameterThatOverridesTheFreeModel() {
        val request = JSONObject().put("model", "gpt-6-astra")
        OpenCodeZenFree.enforceFreeModel(request, "mimo-v2.5-free")
        assertEquals("mimo-v2.5-free", request.getString("model"))
    }
}
