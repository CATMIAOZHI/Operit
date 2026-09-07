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
