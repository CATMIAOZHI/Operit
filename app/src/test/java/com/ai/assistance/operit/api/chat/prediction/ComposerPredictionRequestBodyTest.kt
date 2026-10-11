package com.ai.assistance.operit.api.chat.prediction

import android.content.Context
import com.ai.assistance.operit.api.chat.llmprovider.OpenAIProvider
import com.ai.assistance.operit.api.chat.llmprovider.OpenAIResponsesProvider
import com.ai.assistance.operit.api.chat.llmprovider.SingleApiKeyProvider
import com.ai.assistance.operit.api.chat.llmprovider.applyCallerSuppliedClaudeThinkingParameters
import com.ai.assistance.operit.api.chat.llmprovider.buildGeminiThinkingConfig
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterValueType
import com.ai.assistance.operit.data.model.ParameterCategory
import com.ai.assistance.operit.util.AppLogger
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/**
 * Exercise the existing adapters, rather than assuming they rename the output cap. Tool declarations
 * are not this fixture's subject: predictions mirror the completed turn's tools, and the empty list
 * here only proves the adapters stay well-formed when a turn had none.
 */
class ComposerPredictionRequestBodyTest {
    @Test fun nativeThinkingObjectsReachAdaptersWithTheCompletedTurnsSettings() {
        Mockito.mockStatic(AppLogger::class.java).use {
            val provider = OpenAIResponsesProvider(
                responsesApiEndpoint = "https://example.test/v1/responses",
                apiKeyProvider = SingleApiKeyProvider("test-key"), modelName = "o3-mini",
                client = OkHttpClient(),
            )
            val reasoning = listOf(nativeObject("reasoning", """{"effort":"high","summary":"auto"}"""))
            assertEquals(
                body(provider, reasoning).getJSONObject("reasoning").toString(),
                body(provider, predictionParameters(reasoning)).getJSONObject("reasoning").toString(),
            )
            val claude = listOf(
                nativeObject("thinking", """{"type":"enabled","budget_tokens":8192}"""),
                nativeObject("output_config", """{"effort":"high"}"""),
            )
            val originalClaude = JSONObject()
            val predictionClaude = JSONObject()
            assertTrue(applyCallerSuppliedClaudeThinkingParameters(originalClaude, claude))
            assertTrue(applyCallerSuppliedClaudeThinkingParameters(predictionClaude, predictionParameters(claude)))
            assertEquals(originalClaude.toString(), predictionClaude.toString())

            val gemini = listOf(nativeObject("thinkingConfig",
                """{"thinkingLevel":"high","includeThoughts":true}"""))
            val originalGemini = requireNotNull(buildGeminiThinkingConfig(true, gemini, "gemini-3-pro"))
            val predictionGemini = requireNotNull(buildGeminiThinkingConfig(true, predictionParameters(gemini), "gemini-3-pro"))
            assertEquals("high", predictionGemini.getString("thinkingLevel"))
            assertEquals(originalGemini.toString(), predictionGemini.toString())
        }
    }

    @Test fun carriesTheTurnsOwnOutputCapUnderTheFieldThatTurnUsed() {
        Mockito.mockStatic(AppLogger::class.java).use {
            val chat = body(OpenAIProvider(
                apiEndpoint = "https://example.test/v1/chat/completions",
                apiKeyProvider = SingleApiKeyProvider("test-key"), modelName = "gpt-4.1",
                client = OkHttpClient(), providerType = ApiProviderType.OPENAI, enableToolCall = true,
            ), predictionParameters(cap("max_tokens", 20_000)))
            assertEquals(20_000, chat.getInt("max_tokens"))
            assertFalse(chat.has("max_completion_tokens"))
            assertFalse(chat.has("tools"))

            // The Responses adapter is the one that renames the field, exactly as it does for chat.
            val responses = body(OpenAIResponsesProvider(
                responsesApiEndpoint = "https://example.test/v1/responses",
                apiKeyProvider = SingleApiKeyProvider("test-key"), modelName = "o3-mini",
                client = OkHttpClient(), enableToolCall = true,
            ), predictionParameters(cap("max_tokens", 20_000)))
            assertEquals(20_000, responses.getInt("max_output_tokens"))
            assertFalse(responses.has("max_tokens"))
            assertFalse(responses.has("tools"))
        }
    }

    /**
     * A turn that set no cap must not have one invented for it: the request has to stay cap-free so
     * the provider's own default applies, exactly as it did for the completed turn.
     */
    @Test fun sendsNoCapWhenTheTurnSentNone() {
        Mockito.mockStatic(AppLogger::class.java).use {
            val request = body(OpenAIProvider(
                apiEndpoint = "https://example.test/v1/chat/completions",
                apiKeyProvider = SingleApiKeyProvider("test-key"), modelName = "gpt-4.1",
                client = OkHttpClient(), providerType = ApiProviderType.OPENAI, enableToolCall = true,
            ), predictionParameters(emptyList()))
            assertFalse(request.has("max_tokens"))
            assertFalse(request.has("max_completion_tokens"))
        }
    }

    private fun cap(name: String, value: Int) = listOf(ModelParameter(
        id = name, name = name, apiName = name,
        defaultValue = value, currentValue = value, isEnabled = true, valueType = ParameterValueType.INT,
    ))

    private fun nativeObject(name: String, value: String) = ModelParameter(
        id = name, name = name, apiName = name,
        defaultValue = value, currentValue = value, isEnabled = true,
        valueType = ParameterValueType.OBJECT, category = ParameterCategory.GENERATION,
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
