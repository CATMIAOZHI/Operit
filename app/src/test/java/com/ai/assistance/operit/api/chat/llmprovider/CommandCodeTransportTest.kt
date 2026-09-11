package com.ai.assistance.operit.api.chat.llmprovider

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okio.Buffer
import okio.buffer
import java.io.IOException

class CommandCodeTransportTest {
    private val workspace = "/data/user/0/com.ai.assistance.operit/files/workspace/test-chat"

    @Test fun accountProtocolAndReasoningSummaryMatchTheTransport() {
        val provider = com.ai.assistance.operit.data.model.ApiProviderType.COMMAND_CODE
        assertFalse(com.ai.assistance.operit.data.model.supportsModelProtocolOverrides(provider.name))
        assertEquals(ThinkingRequestSummary.Effort("max"), ThinkingRequestSemantics.resolve(
            providerType = provider, modelName = "deepseek/deepseek-v4-flash",
            qualityLevel = 5, modelParameters = emptyList(),
        ))
        assertNull(CommandCodePolicy.effort("future-model", "none"))
        assertEquals("ultra", CommandCodePolicy.effort("future-model", "ultra"))
    }
    @Test fun historyClosesToolsBeforeSteeringAndKeepsReasoningAndImages() {
        val input = JSONObject("""{"model":"deepseek/deepseek-v4-flash","reasoning_effort":"xhigh",
            "messages":[
            {"role":"system","content":"rules"},
            {"role":"assistant","content":"working","reasoning_content":"reason",
             "tool_calls":[{"id":"a","function":{"name":"read_file","arguments":"{\"path\":\"a\"}"}},
                           {"id":"b","function":{"name":"edit_file","arguments":"{}"}}]},
            {"role":"tool","tool_call_id":"b","content":"done"},
            {"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AA=="}}]}],
            "tools":[{"type":"function","function":{"name":"read_file","parameters":{"type":"object"}}}]}""")
        val body = CommandCodeTransport.compile(input, workspace)
        val params = body.getJSONObject("params")
        assertTrue(params.getBoolean("stream"))
        assertEquals("max", params.getString("reasoning_effort"))
        assertEquals("rules", params.getString("system"))
        val messages = params.getJSONArray("messages")
        val assistant = messages.getJSONObject(0).getJSONArray("content")
        assertEquals("reason", assistant.getJSONObject(1).getString("text"))
        assertEquals("b", messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("toolCallId"))
        val missing = messages.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("a", missing.getString("toolCallId"))
        assertEquals("error-text", missing.getJSONObject("output").getString("type"))
        val image = messages.getJSONObject(3).getJSONArray("content").getJSONObject(0)
        assertEquals("image", image.getString("type"))
        assertEquals("image/png", image.getString("mediaType"))
        assertEquals(4, input.getJSONArray("messages").length())
    }

    @Test fun orphanResultDoesNotBecomeANativeUnpairedToolMessage() {
        val body = CommandCodeTransport.compile(JSONObject("""{"model":"future","messages":[
            {"role":"tool","tool_call_id":"orphan","content":"evidence"}]}"""), workspace)
        val row = body.getJSONObject("params").getJSONArray("messages").getJSONObject(0)
        assertEquals("user", row.getString("role"))
        assertTrue(row.toString().contains("evidence"))
    }

    @Test fun configCarriesTheWorkspaceAndGitBlockTheEndpointRequires() {
        val body = CommandCodeTransport.compile(JSONObject("""{"model":"future","messages":[
            {"role":"user","content":"hi"}]}"""), workspace)
        val config = body.getJSONObject("config")
        assertEquals(workspace, config.getString("workingDir"))
        assertFalse(config.getBoolean("isGitRepo"))
        assertEquals("", config.getString("currentBranch"))
        assertEquals("", config.getString("mainBranch"))
        assertEquals("", config.getString("gitStatus"))
        assertEquals(0, config.getJSONArray("recentCommits").length())
        assertEquals(0, config.getJSONArray("structure").length())
    }

    @Test fun chatWithoutABoundWorkspaceReportsAnEmptyWorkingDir() {
        // A plain chat has no project directory; the app sandbox root must not be presented as one.
        val body = CommandCodeTransport.compile(JSONObject("""{"model":"future","messages":[
            {"role":"user","content":"hi"}]}"""), "")
        assertEquals("", body.getJSONObject("config").getString("workingDir"))
    }

    @Test fun eventsKeepToolIdsReasoningUsageAndOnlyOneFinish() {
        val ndjson = """
            {"type":"reasoning-delta","text":"thinking"}
            {"type":"text-delta","text":"hello"}
            {"type":"tool-call","toolCallId":"b","toolName":"read_file","input":{"path":"b"}}
            {"type":"tool-call","toolCallId":"a","toolName":"edit_file","args":{"path":"a"}}
            {"type":"finish-step","usage":{"inputTokens":10,"outputTokens":3}}
            {"type":"finish","totalUsage":{"inputTokens":12,"outputTokens":4,"inputTokenDetails":{"cacheReadTokens":5,"cacheWriteTokens":2}}}
        """.trimIndent()
        val result = CommandCodeEventSource(Buffer().writeUtf8(ndjson)).buffer().readUtf8()
        val events = result.lines().filter { it.startsWith("data: {") }.map { JSONObject(it.removePrefix("data: ")) }
        assertEquals("thinking", events.first().getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getString("reasoning_content"))
        val call = events[2].getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("b", call.getString("id"))
        assertEquals(0, call.getInt("index"))
        val last = events.last()
        assertEquals("tool_calls", last.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        assertEquals(12, last.getJSONObject("usage").getInt("prompt_tokens"))
        assertEquals(5, last.getJSONObject("usage").getJSONObject("prompt_tokens_details").getInt("cached_tokens"))
        assertEquals(1, result.lines().count { it == "data: [DONE]" })
    }

    @Test fun finishStepAloneFlushesAtEof() {
        val result = CommandCodeEventSource(Buffer().writeUtf8("""{"type":"finish-step","usage":{"inputTokens":1}}"""))
            .buffer().readUtf8()
        assertTrue(result.contains("[DONE]"))
    }

    @Test(expected = IOException::class) fun truncatedStreamDoesNotPretendToSucceed() {
        CommandCodeEventSource(Buffer().writeUtf8("""{"type":"text-delta","text":"partial"}""")).buffer().readUtf8()
    }

    @Test(expected = IOException::class) fun streamErrorsAreNotSilentlyIgnored() {
        CommandCodeEventSource(Buffer().writeUtf8("""{"type":"error","error":{"message":"failed"}}""")).buffer().readUtf8()
    }
}
