package com.ai.assistance.operit.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class CodexModelsClientTest {
    @Test
    fun remoteCatalogShowsOnlyAccountVisibleModelsInPriorityOrder() {
        val models = CodexModelsClient.parseModels(
            """
            {"models":[
              {"slug":"gpt-hidden","display_name":"Hidden","visibility":"hide","priority":1},
              {"slug":"gpt-later","display_name":"Later","visibility":"list","priority":20},
              {"slug":"gpt-now","display_name":"Now","visibility":"list","priority":3,
               "service_tiers":[{"id":"priority"}]},
              {"slug":"gpt-none","display_name":"None","visibility":"none","priority":0}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("gpt-now", "gpt-now-fast", "gpt-later"), models.map { it.id })
        assertEquals("Now Fast", models[1].name)
    }

    @Test
    fun emptyVisibleCatalogDoesNotFallBackToUnrelatedBundledModels() {
        val models = CodexModelsClient.parseModels(
            """{"models":[{"slug":"internal","visibility":"hide"}]}""",
        )
        assertEquals(emptyList<String>(), models.map { it.id })
    }

    @Test
    fun requestUsesTheSignedInAccountAndDoesNotExposeTheTokenInTheUrl() = runBlocking {
        var observedUrl = ""
        var observedAuthorization = ""
        var observedAccount = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            observedUrl = chain.request().url.toString()
            observedAuthorization = chain.request().header("Authorization").orEmpty()
            observedAccount = chain.request().header("ChatGPT-Account-ID").orEmpty()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"models":[{"slug":"gpt-test","visibility":"list"}]}""".toResponseBody())
                .build()
        }.build()

        val models = CodexModelsClient(client).fetch("private-token", "account-1", null)

        assertEquals(listOf("gpt-test"), models.map { it.id })
        assertTrue(observedUrl.startsWith(CodexOAuthProtocol.CODEX_MODELS_ENDPOINT))
        assertTrue(observedUrl.contains("client_version="))
        assertTrue(!observedUrl.contains("private-token"))
        assertEquals("Bearer private-token", observedAuthorization)
        assertEquals("account-1", observedAccount)
    }
}
