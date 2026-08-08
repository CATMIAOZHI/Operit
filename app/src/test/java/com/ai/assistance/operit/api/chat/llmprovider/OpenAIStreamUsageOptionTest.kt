package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.model.ApiProviderType
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIStreamUsageOptionTest {

    @Test
    fun `known chat completions providers request streamed usage`() {
        listOf(ApiProviderType.OPENAI, ApiProviderType.DEEPSEEK, ApiProviderType.MOONSHOT).forEach { provider ->
            val body = JSONObject()
            body.applyChatCompletionsStreamUsageOption(true, provider, useResponsesApi = false)
            assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
        }
    }

    @Test
    fun `generic and local compatible providers omit stream options`() {
        listOf(
            ApiProviderType.OPENAI_GENERIC,
            ApiProviderType.OPENAI_LOCAL,
            ApiProviderType.LMSTUDIO,
            ApiProviderType.OLLAMA,
            ApiProviderType.OTHER,
        ).forEach { provider ->
            val body = JSONObject()
            body.applyChatCompletionsStreamUsageOption(true, provider, useResponsesApi = false)
            assertFalse("$provider must not receive stream_options", body.has("stream_options"))
        }
    }

    @Test
    fun `responses and non streaming requests omit stream options`() {
        val responsesBody = JSONObject()
        responsesBody.applyChatCompletionsStreamUsageOption(true, ApiProviderType.OPENAI, useResponsesApi = true)
        assertFalse(responsesBody.has("stream_options"))

        val nonStreamingBody = JSONObject()
        nonStreamingBody.applyChatCompletionsStreamUsageOption(false, ApiProviderType.OPENAI, useResponsesApi = false)
        assertFalse(nonStreamingBody.has("stream_options"))
    }
}
