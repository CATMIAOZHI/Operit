package com.ai.assistance.operit.api.chat.llmprovider

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexModelListParserTest {
    @Test
    fun directoryWithoutOpenAiUsesBundledCodexModels() {
        val models = CodexModelListFetcher.parseModels("""{"opencode-go":{"models":{}}}""")
        org.junit.Assert.assertTrue(models.any { it.id == "gpt-5.4" })
    }

    @Test
    fun systemInstructionsAreSeparatedWithoutReorderingToolResults() {
        val chatRequest = org.json.JSONObject("""{"messages":[
          {"role":"system","content":"Keep rules"},
          {"role":"user","content":"Hello"},
          {"role":"assistant","tool_calls":[{"id":"call-1","type":"function","function":{"name":"test","arguments":"{}"}}]},
          {"role":"tool","tool_call_id":"call-1","content":"done"}
        ]}""")
        val request = OpenAIResponsesPayloadAdapter.toResponsesRequest(chatRequest)
        CodexRequestAdapter.moveSystemInstructions(request)
        assertEquals("Keep rules", request.getString("instructions"))
        val input = request.getJSONArray("input")
        assertEquals(3, input.length())
        assertEquals("user", input.getJSONObject(0).getString("role"))
        assertEquals("function_call_output", input.getJSONObject(2).getString("type"))
    }

    @Test
    fun parsesOpenCodeCatalogAndExpandsAllowedModes() {
        val models = CodexModelListFetcher.parseModels(
            """
            {
              "openai": {
                "models": {
                  "gpt-5.6-luna": {
                    "id": "gpt-5.6-luna",
                    "name": "GPT-5.6 Luna",
                    "experimental": {
                      "modes": {
                        "fast": {"provider": {"body": {"service_tier": "priority"}}},
                        "pro": {"provider": {"body": {"reasoning": {"mode": "pro"}}}}
                      }
                    }
                  },
                  "gpt-5.4": {
                    "id": "gpt-5.4",
                    "name": "GPT-5.4",
                    "experimental": {
                      "modes": {
                        "fast": {"provider": {"body": {"service_tier": "priority"}}}
                      }
                    }
                  },
                  "gpt-5.5-pro": {
                    "id": "gpt-5.5-pro",
                    "name": "GPT-5.5 Pro"
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            setOf("gpt-5.6-luna", "gpt-5.6-luna-fast", "gpt-5.4", "gpt-5.4-fast"),
            models.map { it.id }.toSet(),
        )
        assertEquals("GPT-5.6 Luna Fast", models.first { it.id == "gpt-5.6-luna-fast" }.name)
    }
}
