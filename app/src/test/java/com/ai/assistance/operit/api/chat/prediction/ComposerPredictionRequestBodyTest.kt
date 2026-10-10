package com.ai.assistance.operit.api.chat.prediction

import android.content.Context
import com.ai.assistance.operit.api.chat.llmprovider.OpenAIProvider
import com.ai.assistance.operit.api.chat.llmprovider.OpenAIResponsesProvider
import com.ai.assistance.operit.api.chat.llmprovider.SingleApiKeyProvider
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ModelProtocol
import com.ai.assistance.operit.data.model.ModelProtocolSettings
import com.ai.assistance.operit.data.model.ParameterValueType
import com.ai.assistance.operit.util.AppLogger
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Exercise the existing adapters, rather than assuming they rename the bounded parameter. */
class ComposerPredictionRequestBodyTest {
    @Test fun reasoningChatUsesCompletionLimitAndNoToolsWhileResponsesUsesOutputLimit() {
        Mockito.mockStatic(AppLogger::class.java).use {
            for (model in listOf("o1", "o3-mini", "gpt-5")) {
                val config = config(model, ApiProviderType.OPENAI)
                val request = body(OpenAIProvider(
                    apiEndpoint = "https://example.test/v1/chat/completions",
                    apiKeyProvider = SingleApiKeyProvider("test-key"), modelName = model,
                    client = OkHttpClient(), providerType = ApiProviderType.OPENAI, enableToolCall = true,
                ), predictionParameters(emptyList(), config))
                assertEquals(192, request.getInt("max_completion_tokens"))
                assertFalse(request.has("max_tokens"))
                assertFalse(request.has("tools"))
            }
            val config = config("o3-mini", ApiProviderType.OPENAI_RESPONSES)
            val response = body(OpenAIResponsesProvider(
                responsesApiEndpoint = "https://example.test/v1/responses",
                apiKeyProvider = SingleApiKeyProvider("test-key"), modelName = "o3-mini",
                client = OkHttpClient(), enableToolCall = true,
            ), predictionParameters(emptyList(), config))
            assertEquals(192, response.getInt("max_output_tokens"))
            assertFalse(response.has("max_completion_tokens"))
            assertFalse(response.has("max_tokens"))
            assertFalse(response.has("tools"))
        }
    }

    @Test fun respectsResolvedProtocolAndConfiguredGenericChatField() {
        val chat = config("custom-model", ApiProviderType.OPENAI_GENERIC)
        val explicit = listOf(ModelParameter(
            id = "limit", name = "limit", apiName = "max_completion_tokens",
            defaultValue = 9999, currentValue = 9999, isEnabled = true, valueType = ParameterValueType.INT,
        ))
        assertEquals("max_completion_tokens", predictionTokenLimitName(chat, explicit))
        for (protocol in listOf(ModelProtocol.RESPONSES, ModelProtocol.ANTHROPIC, ModelProtocol.GEMINI)) {
            val resolved = chat.copy(modelProtocolSettings = mapOf("custom-model" to ModelProtocolSettings(protocol)))
            assertEquals("max_tokens", predictionTokenLimitName(resolved, explicit))
        }
        assertEquals("max_tokens", predictionTokenLimitName(config("gpt-4.1", ApiProviderType.OPENAI), emptyList()))
    }

    private fun config(model: String, provider: ApiProviderType) = ModelConfigData(
        "config", "Chat", modelName = model, apiProviderType = provider, apiProviderTypeId = provider.name,
    )

    private fun body(provider: OpenAIProvider, parameters: List<ModelParameter<*>>): JSONObject {
        val owner = if (provider is OpenAIResponsesProvider) OpenAIResponsesProvider::class.java else OpenAIProvider::class.java
        val method = owner.declaredMethods.single { it.name == "createRequestBody" && it.parameterCount == 7 }
        method.isAccessible = true
        val body = method.invoke(provider, Mockito.mock(Context::class.java), emptyList<Any>(), parameters,
            false, true, emptyList<Any>(), false) as RequestBody
        val buffer = Buffer()
        body.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }
}
