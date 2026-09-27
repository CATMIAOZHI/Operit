package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.api.ClaudeOAuthProtocol
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.StreamLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * The Claude subscription provider (Claude Code OAuth) namespaces custom tools behind
 * [ClaudeOAuthProtocol.TOOL_PREFIX] on the wire while the model-side names stay bare. These tests
 * pin that split so declared tools, replayed tool calls and replayed tool results all agree with
 * each other.
 */
class ClaudeAccountProviderTest {
    @Test
    fun claudeAccountRequestsCarryClaudeCodeIdentityHeadersAndPrefixedTools() = runBlocking {
        var requestBody: JSONObject? = null
        var authorization: String? = null
        var beta: String? = null
        var accept: String? = null
        val responseBody =
            """
            {
              "content": [{"type": "text", "text": "ok"}],
              "usage": {"input_tokens": 1, "output_tokens": 1}
            }
            """.trimIndent()
        val provider =
            ClaudeProvider(
                apiEndpoint = "https://example.test/v1/messages",
                apiKeyProvider = SingleApiKeyProvider("subscription-token"),
                modelName = "claude-sonnet-5",
                client =
                    capturingClient(responseBody, "application/json") { body, request ->
                        requestBody = body
                        authorization = request.header("Authorization")
                        beta = request.header("anthropic-beta")
                        accept = request.header("Accept")
                    },
                providerType = ApiProviderType.CLAUDE_ACCOUNT,
                enableToolCall = true,
            )

        withoutAndroidLogging {
            provider
                .sendMessage(
                    context = Mockito.mock(Context::class.java),
                    chatHistory =
                        listOf(
                            PromptTurn(PromptTurnKind.SYSTEM, "be brief"),
                            PromptTurn(PromptTurnKind.USER, "hello"),
                        ),
                    modelParameters = emptyList(),
                    enableThinking = false,
                    stream = false,
                    availableTools =
                        listOf(
                            ToolPrompt(
                                name = "read_file",
                                description = "read a file",
                                parametersStructured =
                                    listOf(ToolParameterSchema(name = "path", description = "path")),
                            )
                        ),
                    preserveThinkInHistory = false,
                    onTokensUpdated = { _, _, _ -> },
                    onNonFatalError = {},
                    enableRetry = false,
                )
                .collect {}
        }

        val body = requireNotNull(requestBody)
        assertEquals("custom_read_file", body.getJSONArray("tools").getJSONObject(0).getString("name"))

        // The Claude Code identity block has to lead, the caller's prompt follows it.
        val system = body.getJSONArray("system")
        assertEquals(ClaudeOAuthProtocol.SYSTEM_INSTRUCTION, system.getJSONObject(0).getString("text"))
        assertEquals("be brief", system.getJSONObject(1).getString("text"))

        // A subscription token is only accepted as a Bearer with the Claude Code beta header.
        assertEquals("Bearer subscription-token", authorization)
        assertEquals(ClaudeOAuthProtocol.OAUTH_BETA, beta)
        // Non-streaming requests ask for JSON, streaming ones for SSE, like the reference does.
        assertEquals("application/json", accept)
    }

    @Test
    fun claudeAccountReplayPrefersPrefixedWireNamesAndKeepsToolResultsReal() = runBlocking {
        var requestBody: JSONObject? = null
        val responseBody =
            """
            {
              "content": [{"type": "text", "text": "ok"}],
              "usage": {"input_tokens": 1, "output_tokens": 1}
            }
            """.trimIndent()
        val provider =
            ClaudeProvider(
                apiEndpoint = "https://example.test/v1/messages",
                apiKeyProvider = SingleApiKeyProvider("subscription-token"),
                modelName = "claude-sonnet-5",
                client =
                    capturingClient(responseBody, "application/json") { body, _ ->
                        requestBody = body
                    },
                providerType = ApiProviderType.CLAUDE_ACCOUNT,
                enableToolCall = true,
            )

        withoutAndroidLogging {
            provider
                .sendMessage(
                    context = Mockito.mock(Context::class.java),
                    chatHistory =
                        listOf(
                            PromptTurn(PromptTurnKind.USER, "read that file"),
                            PromptTurn(
                                PromptTurnKind.ASSISTANT,
                                """<tool name="read_file"><param name="path">/tmp/a.txt</param></tool>""",
                            ),
                            PromptTurn(
                                PromptTurnKind.TOOL_RESULT,
                                """<tool_result name="read_file" status="success"><content>ACTUAL FILE BODY</content></tool_result>""",
                            ),
                        ),
                    modelParameters = emptyList(),
                    enableThinking = false,
                    stream = false,
                    availableTools = null,
                    preserveThinkInHistory = false,
                    onTokensUpdated = { _, _, _ -> },
                    onNonFatalError = {},
                    enableRetry = false,
                )
                .collect {}
        }

        val messages = requireNotNull(requestBody).getJSONArray("messages")
        val replayedCall = messages.getJSONObject(1).getJSONArray("content").getJSONObject(0)
        // The replayed declaration must match what the model was told the tool is called.
        assertEquals("tool_use", replayedCall.getString("type"))
        assertEquals("custom_read_file", replayedCall.getString("name"))

        val toolResult = messages.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        // The bare name used to prefix the call broke the match, so the real output was replaced by
        // the "工具结果缺失" placeholder.
        assertEquals("tool_result", toolResult.getString("type"))
        assertEquals(replayedCall.getString("id"), toolResult.getString("tool_use_id"))
        val resultContent = toolResult.get("content").toString()
        assertTrue(resultContent.contains("ACTUAL FILE BODY"))
        assertFalse(resultContent.contains("工具结果缺失"))
    }

    @Test
    fun claudeAccountStripsThePrefixFromStreamedToolUses() = runBlocking {
        var accept: String? = null
        val sseBody =
            listOf(
                """data: {"type":"content_block_start","content_block":{"type":"tool_use","name":"custom_write_file","input":{"path":"/tmp/out"}}}""",
                """data: {"type":"content_block_stop"}""",
                """data: {"type":"message_stop"}""",
            ).joinToString(separator = "\n\n", postfix = "\n\n")
        val provider =
            ClaudeProvider(
                apiEndpoint = "https://example.test/v1/messages",
                apiKeyProvider = SingleApiKeyProvider("subscription-token"),
                modelName = "claude-sonnet-5",
                client =
                    capturingClient(sseBody, "text/event-stream") { _, request ->
                        accept = request.header("Accept")
                    },
                providerType = ApiProviderType.CLAUDE_ACCOUNT,
                enableToolCall = true,
            )

        val output =
            withoutAndroidLogging {
                val buffer = StringBuilder()
                provider
                    .sendMessage(
                        context = Mockito.mock(Context::class.java),
                        chatHistory = listOf(PromptTurn(PromptTurnKind.USER, "write it")),
                        modelParameters = emptyList(),
                        enableThinking = false,
                        stream = true,
                        availableTools = null,
                        preserveThinkInHistory = false,
                        onTokensUpdated = { _, _, _ -> },
                        onNonFatalError = {},
                        enableRetry = false,
                    )
                    .collect { buffer.append(it) }
                buffer.toString()
            }

        // Execution and history pairing use the bare name; only the wire copy is namespaced.
        assertTrue(output, output.contains("""name="write_file""""))
        assertFalse(output, output.contains("""name="custom_write_file""""))
        assertEquals("text/event-stream", accept)
    }

    private fun capturingClient(
        body: String,
        mediaType: String,
        onRequest: (JSONObject, okhttp3.Request) -> Unit,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                onRequest(JSONObject(buffer.readUtf8()), chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", mediaType)
                    .body(body.toResponseBody(mediaType.toMediaType()))
                    .build()
            }
            .build()

    private suspend fun <T> withoutAndroidLogging(block: suspend () -> T): T =
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
