package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.stream.StreamLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class GenericReasoningProtocolTest {
    @Test
    fun declaredEffortIsSentWithoutKimiThinkingFieldsAndCanBeSuppressed() = runBlocking {
        withContext(Dispatchers.IO) {
            Mockito.mockStatic(AppLogger::class.java).use {
                for (efforts in listOf(listOf("low", "high", "max"), emptyList())) {
                    val provider = object : KimiProvider(
                        "https://opencode.ai/zen/go/v1/chat/completions",
                        SingleApiKeyProvider("test-only-key"), "deepseek-v4-flash", OkHttpClient(),
                        providerType = ApiProviderType.OPENAI_GENERIC,
                        configureThinking = false,
                        reasoningEfforts = efforts,
                    ) {
                        override fun resolveOpenAiChatReasoningEffort(context: Context) = "xhigh"
                        fun request(thinking: Boolean, suppressed: Boolean = false, functionalLevel: Int? = null): JSONObject {
                            val parameters = if (suppressed) listOf(
                                com.ai.assistance.operit.data.model.ModelParameter(
                                    id = "internal", name = "internal", apiName = SUPPRESS_AUTOMATIC_REASONING_API_NAME,
                                    description = "", defaultValue = true, currentValue = true,
                                    valueType = com.ai.assistance.operit.data.model.ParameterValueType.BOOLEAN,
                                    category = com.ai.assistance.operit.data.model.ParameterCategory.OTHER,
                                    isEnabled = true,
                                ),
                            ) else emptyList()
                            val functional = functionalLevel?.let {
                                buildFunctionalReasoningRequest(
                                    ApiProviderType.OPENAI_GENERIC, "deepseek-v4-flash", parameters, it,
                                    reasoningEfforts = efforts,
                                )
                            }
                            val body = createRequestBody(Mockito.mock(Context::class.java),
                                listOf(PromptTurn(PromptTurnKind.USER, "Reply OK.")),
                                functional?.modelParameters ?: parameters,
                                functional?.enableThinking ?: thinking, false, null, false)
                            val buffer = Buffer()
                            body.writeTo(buffer)
                            return JSONObject(buffer.readUtf8())
                        }
                    }
                    val enabled = provider.request(true)
                    assertEquals(if (efforts.isEmpty()) "xhigh" else "max", enabled.getString("reasoning_effort"))
                    assertFalse(enabled.has("thinking"))
                    if (efforts.isEmpty()) assertEquals("none", provider.request(false).getString("reasoning_effort"))
                    else assertFalse(provider.request(false).has("reasoning_effort"))
                    val suppressed = provider.request(true, suppressed = true)
                    assertFalse(suppressed.has("reasoning_effort"))
                    assertFalse(suppressed.has(SUPPRESS_AUTOMATIC_REASONING_API_NAME))
                    assertEquals("low", provider.request(true, functionalLevel = 1).getString("reasoning_effort"))
                    assertEquals(if (efforts.isEmpty()) "medium" else "high", provider.request(true, functionalLevel = 2).getString("reasoning_effort"))
                }
            }
        }
    }

    @Test
    fun replayDoesNotDependOnThinkingSwitchOrSendKimiControls() = runBlocking {
        withContext(Dispatchers.IO) {
            Mockito.mockStatic(AppLogger::class.java).use {
                StreamLogger.setEnabled(false)
                try {
                    val provider = object : KimiProvider(
                        "https://example.test/v1/chat/completions",
                        SingleApiKeyProvider("test-only-key"), "reasoning-model", OkHttpClient(),
                        providerType = ApiProviderType.OPENAI_GENERIC,
                        configureThinking = false,
                    ) {
                        override fun resolveOpenAiChatReasoningEffort(context: Context) = "xhigh"
                        fun request(thinking: Boolean): JSONObject {
                            val history = listOf(
                                PromptTurn(PromptTurnKind.USER, "question"),
                                PromptTurn(PromptTurnKind.ASSISTANT,
                                    "${ChatUtils.PROVIDER_REASONING_OPEN_TAG}remember this</think>answer"),
                                PromptTurn(PromptTurnKind.USER, "continue"),
                            )
                            val body = createRequestBody(Mockito.mock(Context::class.java),
                                history, emptyList(), thinking, false, null, false)
                            val buffer = Buffer()
                            body.writeTo(buffer)
                            return JSONObject(buffer.readUtf8())
                        }
                    }
                    for (thinking in listOf(false, true)) {
                        val request = provider.request(thinking)
                        assertEquals(if (thinking) "xhigh" else "none", request.getString("reasoning_effort"))
                        assertFalse(request.has("thinking"))
                        val messages = request.getJSONArray("messages")
                        val assistant = (0 until messages.length()).map { messages.getJSONObject(it) }
                            .single { it.getString("role") == "assistant" }
                        assertEquals("remember this", assistant.getString("reasoning_content"))
                        assertFalse(assistant.getString("content").contains("remember this"))
                    }
                } finally {
                    StreamLogger.setEnabled(true)
                }
            }
        }
    }
}
