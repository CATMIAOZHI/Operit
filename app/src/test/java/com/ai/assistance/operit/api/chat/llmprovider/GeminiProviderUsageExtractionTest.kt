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

    private fun provider() = GeminiProvider(
        apiEndpoint = "https://example.invalid",
        apiKeyProvider = SingleApiKeyProvider("test-key"),
        modelName = "gemini-test",
        client = OkHttpClient(),
        providerType = ApiProviderType.GOOGLE,
    )

    /** Feeds the stream's frames in order and returns the last counts the chat was told. */
    private fun counts(provider: GeminiProvider, vararg frames: String): Triple<Int, Int, Int>? = runBlocking {
        var last: Triple<Int, Int, Int>? = null
        Mockito.mockStatic(AppLogger::class.java).use {
            frames.forEach { frame ->
                provider.extractContentFromJson(
                    context = Mockito.mock(Context::class.java),
                    json = JSONObject(frame),
                    requestId = "request-1",
                    onTokensUpdated = { input, cached, output -> last = Triple(input, cached, output) },
                    onUsageReported = { _, _ -> },
                    attemptNumber = 1,
                )
            }
        }
        last
    }

    @Test
    fun `a frame reporting only the output keeps the input counted before`() {
        val result = counts(
            provider(),
            """{"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":40},"candidates":[]}""",
            """{"usageMetadata":{"candidatesTokenCount":9},"candidates":[]}""",
        )
        assertEquals(Triple(100, 40, 9), result)
    }

    @Test
    fun `text in a frame whose usage already counts the output is not estimated on top`() {
        val result = counts(
            provider(),
            """{"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":5},
               "candidates":[{"content":{"parts":[{"text":"hello there, this is a fairly long reply"}]}}]}""",
        )
        assertEquals(Triple(100, 0, 5), result)
    }

    @Test
    fun `a cache hit reported earlier stands when a later frame leaves the field out`() {
        val result = counts(
            provider(),
            """{"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":40},"candidates":[]}""",
            """{"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":9},"candidates":[]}""",
        )
        assertEquals(Triple(100, 40, 9), result)
    }

    @Test
    fun `an explicit zero cache count replaces the earlier hit`() {
        val result = counts(
            provider(),
            """{"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":40},"candidates":[]}""",
            """{"usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":0,"candidatesTokenCount":9},"candidates":[]}""",
        )
        assertEquals(Triple(100, 0, 9), result)
    }
}
