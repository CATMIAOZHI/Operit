package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.Mockito

class GeminiProviderUsageExtractionTest {

    @Test
    fun `usage metadata is reported when response has no candidates`() = runBlocking {
        val provider =
            GeminiProvider(
                apiEndpoint = "https://example.invalid",
                apiKeyProvider = SingleApiKeyProvider("test-key"),
                modelName = "gemini-test",
                client = OkHttpClient(),
                providerType = ApiProviderType.GOOGLE,
            )
        var callbackCounts: Triple<Int, Int, Int>? = null
        var reported: ProviderUsageSnapshot? = null
        var reportedAttempt: Int? = null
        val response =
            JSONObject(
                """
                {
                  "usageMetadata": {
                    "promptTokenCount": 120,
                    "cachedContentTokenCount": 20,
                    "candidatesTokenCount": 7
                  },
                  "candidates": []
                }
                """.trimIndent()
            )

        val content = Mockito.mockStatic(AppLogger::class.java).use {
            provider.extractContentFromJson(
                context = Mockito.mock(Context::class.java),
                json = response,
                requestId = "request-1",
                onTokensUpdated = { input, cached, output ->
                    callbackCounts = Triple(input, cached, output)
                },
                onUsageReported = { usage, attempt ->
                    reported = usage
                    reportedAttempt = attempt
                },
                attemptNumber = 3,
            )
        }

        assertEquals("", content)
        assertEquals(Triple(120, 20, 7), callbackCounts)
        assertNotNull(reported)
        assertEquals(120L, reported!!.totalInputTokens)
        assertEquals(100L, reported!!.uncachedInputTokens)
        assertEquals(20L, reported!!.cachedInputTokens)
        assertEquals(7L, reported!!.outputTokens)
        assertEquals(3, reportedAttempt)
    }
}
