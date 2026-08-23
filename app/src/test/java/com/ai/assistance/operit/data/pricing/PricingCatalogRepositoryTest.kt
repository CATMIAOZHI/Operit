package com.ai.assistance.operit.data.pricing

import android.content.Context
import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PricingCatalogRepositoryTest {
    @Test
    fun `concurrent forced refreshes share one active job and request`() {
        val tempDir = Files.createTempDirectory("pricing-catalog-test").toFile()
        val context = mock<Context>()
        val assets = mock<AssetManager>()
        whenever(context.assets).thenReturn(assets)
        whenever(context.noBackupFilesDir).thenReturn(tempDir)
        whenever(assets.open("pricing/model_pricing_v1.json"))
            .thenAnswer { ByteArrayInputStream(validDocument.toByteArray()) }

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(validDocument.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val repository = PricingCatalogRepository(
            context = context,
            httpClient = client,
            remoteUrl = "https://example.test/pricing.json",
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val first = repository.refresh(scope, force = true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = repository.refresh(scope, force = true)

            assertSame(first, second)
            release.countDown()
            runBlocking { first.join() }
            assertEquals(1, requests.get())
        } finally {
            release.countDown()
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

    private val validDocument = """
        {
          "schemaVersion": 1,
          "revision": "test",
          "generatedAt": "2026-08-24T00:00:00Z",
          "entries": [
            {
              "provider": "provider-a",
              "model": "model-a",
              "billingMode": "TOKEN",
              "currency": "USD",
              "input": 1.0,
              "cacheRead": 0.5,
              "cacheWrite": null,
              "output": 2.0,
              "perRequest": null,
              "aliases": [],
              "sourceUrl": null,
              "verifiedAt": null
            }
          ]
        }
    """.trimIndent()
}
