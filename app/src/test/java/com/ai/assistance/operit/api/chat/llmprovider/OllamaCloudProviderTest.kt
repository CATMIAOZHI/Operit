package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.data.collects.ApiProviderConfigs
import com.ai.assistance.operit.data.model.ApiProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class OllamaCloudProviderTest {
    @Test
    fun cloudProviderUsesHostedEndpointAndRequiresKey() {
        val type = ApiProviderType.OLLAMA_CLOUD
        val endpoint = ApiProviderConfigs.getDefaultApiEndpoint(type)

        assertEquals("https://ollama.com/v1/chat/completions", endpoint)
        assertTrue(ApiProviderConfigs.requiresApiKey(type, endpoint))
    }

    @Test
    fun cloudCatalogUsesApiModelNames() {
        val response = """
            {"models":[{"name":"gemma4:31b","model":"gemma4:31b"},{"name":"gpt-oss:120b","model":"gpt-oss:120b"}]}
        """.trimIndent()

        val models = ModelListFetcher.parseOllamaCloudModelResponse(Mockito.mock(Context::class.java), response)

        assertEquals(listOf("gemma4:31b", "gpt-oss:120b"), models.map { it.id })
    }
}
