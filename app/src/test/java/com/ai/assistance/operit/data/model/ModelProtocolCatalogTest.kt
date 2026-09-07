package com.ai.assistance.operit.data.model

import org.junit.Assert.*
import org.junit.Test

class ModelProtocolCatalogTest {
    private val catalog = ModelProtocolCatalog.parse("""
        {
          "opencode": {
            "api":"https://opencode.ai/zen/v1", "npm":"@ai-sdk/openai-compatible",
            "models":{"same":{"provider":{"npm":"@ai-sdk/openai"}}}
          },
          "opencode-go": {
            "api":"https://opencode.ai/zen/go/v1", "npm":"@ai-sdk/openai-compatible",
            "models":{
              "same":{"interleaved":{"field":"reasoning_content"}},
              "gpt":{"provider":{"npm":"@ai-sdk/openai"}},
              "minimax":{"provider":{"npm":"@ai-sdk/anthropic"}},
              "qwen3.8-max":{},
              "unknown-reasoning":{"interleaved":{"field":"unsupported_field"}}
            }
          },
          "original": {
            "api":"https://original.example/v1", "npm":"@ai-sdk/openai",
            "models":{"same":{}}
          }
        }
    """.trimIndent())

    @Test
    fun goUsesItsOwnProviderAndModelMetadata() {
        val matched = catalog.matchAll("https://opencode.ai/zen/go/v1/chat/completions",
            listOf("same", "gpt", "minimax", "qwen3.8-max", "unknown-reasoning", "missing"))
        assertEquals(4, matched.size)
        assertEquals(ModelProtocol.CHAT_REASONING, matched["same"]?.protocol)
        assertEquals(ModelProtocol.RESPONSES, matched["gpt"]?.protocol)
        assertEquals(ModelProtocol.ANTHROPIC, matched["minimax"]?.protocol)
        assertEquals(ModelProtocol.ANTHROPIC, matched["qwen3.8-max"]?.protocol)
    }

    @Test
    fun modelAuthorAndZenDoNotOverrideGoProtocol() {
        assertEquals(ModelProtocol.RESPONSES, catalog.matchAll(
            "https://original.example/v1/chat/completions", listOf("same"))["same"]?.protocol)
        assertEquals(ModelProtocol.RESPONSES, catalog.matchAll(
            "https://opencode.ai/zen/v1/responses", listOf("same"))["same"]?.protocol)
        assertTrue(catalog.matchAll("https://unknown.example/v1", listOf("same")).isEmpty())
        assertTrue(catalog.matchAll("https://opencode.ai/zen/go/v10", listOf("same")).isEmpty())
        assertTrue(catalog.matchAll("https://opencode.ai.example/zen/go/v1", listOf("same")).isEmpty())
    }
}
