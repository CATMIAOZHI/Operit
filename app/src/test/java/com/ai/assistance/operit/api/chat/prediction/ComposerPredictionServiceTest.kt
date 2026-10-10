package com.ai.assistance.operit.api.chat.prediction

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.MultiServiceManager
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.api.chat.llmprovider.OpenCodeSessionContext
import com.ai.assistance.operit.api.chat.llmprovider.SUPPRESS_AUTOMATIC_REASONING_API_NAME
import com.ai.assistance.operit.api.chat.llmprovider.providerSessionIdForScope
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.AbstractStream
import com.ai.assistance.operit.util.stream.StreamCollector
import com.ai.assistance.operit.util.stream.stream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class ComposerPredictionServiceTest {
    private val context = Mockito.mock(Context::class.java)
    private val config = ModelConfigData("chat-config", "Chat", modelName = "chat-model")
    private val history = listOf(
        PromptTurn(PromptTurnKind.SYSTEM, "Stable personalized prompt"),
        PromptTurn(PromptTurnKind.USER, "Help me choose"),
        PromptTurn(PromptTurnKind.ASSISTANT, "Here is a comparison"),
    )
    private val snapshot = ComposerPredictionSnapshot("chat-1", "turn-1", history, config, emptyList())

    @Test fun preservesPrefixAndAddsOnlyTailInstruction() {
        val request = predictionHistory(history)
        assertEquals(history, request.dropLast(1))
        assertEquals(PromptTurnKind.USER, request.last().kind)
        assertEquals(3, history.size)
    }

    @Test fun rejectsProtocolReasoningAndMultipleOrMeaninglessSuggestions() {
        listOf("", "NO_PREDICTION", "...", "null", "<think>Reasoning</think>",
            "<tool name=\"shell\">run</tool>", "{\"message\":\"next\"}",
            "First suggestion\nSecond suggestion", "1. Next message", "Suggestion: More details",
            "x".repeat(241), "Error: Request failed", "谢谢！", "好的", "你好", "Thanks.", "Thank you", "Hi!").forEach { assertNull(it, validatePrediction(it)) }
        assertEquals("Can you explain the tradeoffs?", validatePrediction(" Can you explain the tradeoffs? "))
        assertEquals("详细说说", validatePrediction("详细说说"))
        assertEquals("好的，运行测试", validatePrediction("好的，运行测试"))
        assertEquals("Thanks, can you compare costs?", validatePrediction("Thanks, can you compare costs?"))
    }

    @Test fun stripsDangerousCustomParametersAndAlwaysBoundsTokens() {
        val unsafe = listOf("tools", "messages", "max_output_tokens", "max_completion_tokens", "generationConfig", "n", "response_format", "previous_response_id")
        val params = unsafe.map { parameter(it, 99999) } + parameter("temperature", 1) +
            parameter("top_p", 1).copy(isEnabled = false)
        val result = predictionParameters(params, config)
        assertEquals(setOf("temperature", "max_tokens", SUPPRESS_AUTOMATIC_REASONING_API_NAME), result.map { it.apiName }.toSet())
        assertEquals(192, result.single { it.apiName == "max_tokens" }.currentValue)
        assertEquals(99999, params.first().currentValue)
        for (provider in listOf(ApiProviderType.GOOGLE, ApiProviderType.ANTHROPIC, ApiProviderType.CLAUDE_ACCOUNT, ApiProviderType.GOOGLE_ANTIGRAVITY, ApiProviderType.ALIYUN, ApiProviderType.SILICONFLOW, ApiProviderType.DOUBAO, ApiProviderType.NVIDIA, ApiProviderType.OPENROUTER, ApiProviderType.NOUS_PORTAL)) {
            val native = config.copy(apiProviderType = provider, apiProviderTypeId = provider.name)
            assertFalse(predictionParameters(params, native).any { it.apiName == SUPPRESS_AUTOMATIC_REASONING_API_NAME })
        }
    }

    @Test fun disablesSearchAndForegroundIdentityButRetainsUnexpectedToolDetection() {
        val original = config.copy(enableGoogleSearch = true, enableToolCall = true,
            customHeaders = """{"x-opencode-session":"foreground","Authorization":"custom"}""")
        val result = predictionConfig(original)
        assertFalse(result.enableGoogleSearch)
        assertTrue(result.enableToolCall)
        assertEquals("custom", JSONObject(result.customHeaders).getString("Authorization"))
        assertFalse(JSONObject(result.customHeaders).has("x-opencode-session"))
        assertTrue(original.enableGoogleSearch)
    }

    @Test fun explicitlyRejectsUnknownAndRetiredProviders() {
        assertFalse(ComposerPredictionService.supports(config.copy(apiProviderTypeId = "plugin-provider")))
        assertFalse(ComposerPredictionService.supports(config.copy(apiProviderTypeId = "MNN")))
        assertFalse(ComposerPredictionService.supports(config.copy(modelName = "gemini-3.1-flash-image")))
        assertFalse(ComposerPredictionService.supports(config.copy(apiProviderTypeId = "OPENCODE_ZEN_FREE")))
        assertFalse(ComposerPredictionService.supports(config.copy(apiProviderTypeId = "OPENAI_CODEX")))
        assertTrue(ComposerPredictionService.supports(config))
    }

    @Test fun requestUsesAuxiliaryCategoryIndependentInstancesAndNoTools() = runBlocking {
        val instances = mutableListOf<FakeService>()
        val runner = runner { FakeService().also(instances::add) }
        assertEquals("Tell me more", runner.predict(snapshot))
        assertEquals("Tell me more", runner.predict(snapshot))
        assertEquals(2, instances.size)
        instances.forEach {
            assertEquals(TokenStatCategory.COMPOSER_PREDICTION, it.category)
            assertEquals(emptyList<ToolPrompt>(), it.tools)
            assertFalse(it.thinking)
            assertFalse(it.retry)
            assertEquals(providerSessionIdForScope("composer-prediction:chat-1"), it.session)
            assertTrue(it.cancelled)
            assertTrue(it.released)
        }
    }

    @Test fun timeoutClosesBlockedIndependentCallAndReleasesIt() = runBlocking {
        val fake = FakeService(block = true)
        try {
            runner(timeoutMs = 100) { fake }.predict(snapshot)
            fail("Expected timeout")
        } catch (_: TimeoutCancellationException) {
            assertTrue(fake.cancelled)
            assertTrue(fake.released)
        }
    }

    @Test fun errorsNeverBecomeComposerTextAndStillReleaseService() = runBlocking {
        val fake = FakeService(fail = true)
        try {
            runner { fake }.predict(snapshot)
            fail("Expected failure")
        } catch (error: IOException) {
            assertEquals("Provider failed", error.message)
            assertTrue(fake.released)
        }
        val warning = FakeService(warn = true)
        try {
            runner { warning }.predict(snapshot)
            fail("Expected provider warning to fail prediction")
        } catch (error: IOException) {
            assertEquals("Prediction provider reported an error", error.message)
            assertTrue(warning.released)
        }
    }

    private fun runner(timeoutMs: Long = 2_000, factory: () -> FakeService) = ComposerPredictionService(
        context, { model, parameters ->
            val service = factory()
            MultiServiceManager.ServiceLease({ service.release() }, service, model, 0, parameters)
        }, timeoutMs,
    )

    private fun parameter(name: String, value: Int) = ModelParameter(
        name, name, name, defaultValue = value, currentValue = value,
        isEnabled = true, valueType = ParameterValueType.INT,
    )

    private class FakeService(val block: Boolean = false, val fail: Boolean = false, val warn: Boolean = false) : AIService {
        override val inputTokenCount = 0
        override val cachedInputTokenCount = 0
        override val outputTokenCount = 0
        override val providerModel = "fake:chat"
        @Volatile var cancelled = false
        var released = false
        var category: TokenStatCategory? = null
        var tools: List<ToolPrompt>? = null
        var thinking = true
        var retry = true
        var session: String? = null
        private val stop = CountDownLatch(1)
        override fun resetTokenCounts() = Unit
        override fun cancelStreaming() { cancelled = true; stop.countDown() }
        override fun release() { released = true }
        override suspend fun getModelsList(context: Context) = Result.success(emptyList<ModelOption>())
        override suspend fun testConnection(context: Context, onUsageReported: (suspend (ProviderUsageSnapshot, Int) -> Unit)?) = Result.success("")
        override suspend fun calculateInputTokens(chatHistory: List<PromptTurn>, availableTools: List<ToolPrompt>?) = 0
        override suspend fun sendMessage(
            context: Context, chatHistory: List<PromptTurn>, modelParameters: List<ModelParameter<*>>,
            enableThinking: Boolean, stream: Boolean, availableTools: List<ToolPrompt>?, preserveThinkInHistory: Boolean,
            onTokensUpdated: suspend (Int, Int, Int) -> Unit,
            onUsageReported: (suspend (ProviderUsageSnapshot, Int) -> Unit)?,
            onNonFatalError: suspend (String) -> Unit, enableRetry: Boolean, statsCategory: TokenStatCategory?,
        ): Stream<String> {
            category = statsCategory; tools = availableTools; thinking = enableThinking; retry = enableRetry
            session = currentCoroutineContext()[OpenCodeSessionContext]?.sessionId
            // Throw directly from the provider boundary. StreamBuilders logs failures through
            // Android APIs on Dispatchers.IO, outside a test-thread static logging mock.
            if (fail) return object : AbstractStream<String>() {
                override suspend fun collect(collector: StreamCollector<String>) {
                    throw IOException("Provider failed")
                }
                override suspend fun emitBufferedItem(item: String) {
                    error("Failing provider fixture must not emit buffered content")
                }
            }
            return com.ai.assistance.operit.util.stream.stream {
                if (block) check(stop.await(2, TimeUnit.SECONDS)) { "Cancellation did not close blocked call" }
                if (warn) onNonFatalError("Provider stream error")
                emit("Tell me more")
            }
        }
    }
}
