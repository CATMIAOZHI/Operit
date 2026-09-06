package com.ai.assistance.operit.data.pricing

import android.content.Context
import android.content.res.AssetManager
import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import com.ai.assistance.operit.data.stats.TokenPriceResolver
import com.ai.assistance.operit.data.model.BillingMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PricingCatalogRepositoryTest {
    @Test
    fun `dev and stable builds use their matching catalog branches`() {
        assertEquals(
            PricingCatalogRepository.DEV_REMOTE_URL,
            PricingCatalogRepository.remoteUrlFor(personalDev = true),
        )
        assertEquals(
            PricingCatalogRepository.MAIN_REMOTE_URL,
            PricingCatalogRepository.remoteUrlFor(personalDev = false),
        )
    }

    @Test
    fun `concurrent manual refreshes share one active job and request`() {
        val tempDir = Files.createTempDirectory("pricing-catalog-test").toFile()
        val context = mock<Context>()
        val assets = mock<AssetManager>()
        whenever(context.assets).thenReturn(assets)
        whenever(context.noBackupFilesDir).thenReturn(tempDir)
        whenever(assets.open("pricing/official_model_pricing_v1.json"))
            .thenAnswer { ByteArrayInputStream(validDocument.toByteArray()) }

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.host == "models.dev") throw SocketTimeoutException("live timeout")
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
            cacheWriter = ::writeTestCache,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val first = repository.refresh(scope)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = repository.refresh(scope)

            assertSame(first, second)
            release.countDown()
            runBlocking { first.join() }
            assertEquals(1, requests.get())
            assertNull(repository.state.value.lastError)
            assertTrue(repository.state.value.canApply)
        } finally {
            release.countDown()
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `downloaded official cache is pending and startup does not download`() = withFixture { fixture ->
        writeTestCache(
            File(fixture.directory, "operit/pricing/official_model_pricing_v1.json"),
            document("old-cache", 4.0).toByteArray(),
        )
        val repository = fixture.repository()
        assertEquals(0, fixture.requests.get())
        assertEquals("test", repository.snapshot.revision)
        assertEquals("old-cache", repository.state.value.downloadedRevision)
        assertTrue(repository.state.value.canApply)
    }

    @Test
    fun `download and application remain separate across restarts and later downloads`() = withFixture { fixture ->
        val repository = fixture.repository()
        repository.refresh(fixture.scope).join()
        assertEquals("test", repository.snapshot.revision)
        assertEquals(1.0, currentInput(), 0.0)
        assertEquals("remote-1", repository.state.value.downloadedRevision)

        val restarted = fixture.repository()
        assertEquals("test", restarted.snapshot.revision)
        restarted.applyDownloaded(fixture.scope).join()
        assertEquals(4.0, currentInput(), 0.0)
        assertFalse(restarted.state.value.canApply)
        assertNull(restarted.state.value.lastError)

        fixture.remote = document("remote-2", 8.0)
        restarted.refresh(fixture.scope).join()
        assertEquals("remote-1", restarted.snapshot.revision)
        assertEquals(4.0, currentInput(), 0.0)
        val restartedAgain = fixture.repository()
        assertEquals("remote-1", restartedAgain.snapshot.revision)
        assertEquals("remote-2", restartedAgain.state.value.downloadedRevision)
        assertTrue(restartedAgain.state.value.canApply)
        restartedAgain.applyDownloaded(fixture.scope).join()
        assertEquals(8.0, currentInput(), 0.0)
        assertEquals("remote-2", fixture.repository().snapshot.revision)
    }

    @Test
    fun `failed refresh keeps both applied prices and previous pending download`() = withFixture { fixture ->
        val repository = fixture.repository()
        repository.refresh(fixture.scope).join()
        repository.applyDownloaded(fixture.scope).join()
        fixture.remote = document("remote-2", 8.0)
        repository.refresh(fixture.scope).join()
        fixture.remote = "invalid json"
        repository.refresh(fixture.scope).join()
        assertTrue(repository.state.value.lastError != null)
        assertEquals("remote-1", repository.snapshot.revision)
        assertEquals("remote-2", repository.state.value.downloadedRevision)
        assertEquals("remote-1", fixture.repository().snapshot.revision)
    }

    @Test
    fun `failed application does not change effective or persisted prices and can be retried`() = withFixture { fixture ->
        val repository = fixture.repository()
        repository.refresh(fixture.scope).join()
        fixture.failApplication = true
        repository.applyDownloaded(fixture.scope).join()
        assertEquals("disk full", repository.state.value.lastError)
        assertTrue(repository.state.value.canApply)
        assertEquals(1.0, currentInput(), 0.0)
        assertEquals("test", fixture.repository().snapshot.revision)
        fixture.failApplication = false
        repository.applyDownloaded(fixture.scope).join()
        assertEquals(4.0, currentInput(), 0.0)
        assertEquals("remote-1", fixture.repository().snapshot.revision)
    }

    @Test
    fun `applied cloud defaults stay below manual overrides and leave resolved snapshots intact`() = withFixture { fixture ->
        val repository = fixture.repository()
        val override = TokenPriceResolver.normalizedOverride(
            scope = TokenPriceResolver.SCOPE_CONFIG,
            provider = "provider-a",
            model = "model-a",
            configId = "my-config",
            billingMode = BillingMode.TOKEN,
            pricingCurrency = "USD",
            inputPricePerMillion = 0.0,
        )
        fun resolve() = TokenPriceResolver.resolve(
            "provider-a", "model-a", "my-config", listOf(override), null,
            DefaultModelPricingCollect.getDefaultPricing("provider-a:model-a"),
        )
        val frozenDefaults = DefaultModelPricingCollect.getDefaultPricing("provider-a:model-a")
        val before = resolve()
        repository.refresh(fixture.scope).join()
        repository.applyDownloaded(fixture.scope).join()
        assertEquals(4.0, currentInput(), 0.0)
        assertEquals(0.0, resolve().inputPricePerMillion!!, 0.0)
        assertEquals(before, resolve())
        assertEquals(1.0, frozenDefaults.inputPricePerMillion, 0.0)
    }

    private fun currentInput() =
        DefaultModelPricingCollect.getDefaultPricing("provider-a:model-a").inputPricePerMillion

    @Test
    fun `live sources are preferred without applying downloaded prices`() = withFixture { fixture ->
        fixture.live = true
        val repository = fixture.repository()
        repository.refresh(fixture.scope).join()
        assertNull(repository.state.value.lastError)
        assertEquals(0, fixture.requests.get())
        assertTrue(repository.state.value.downloadedRevision!!.startsWith("official-"))
        assertEquals("test", repository.snapshot.revision)
        repository.applyDownloaded(fixture.scope).join()
        assertEquals(9.0, currentInput(), 0.0)
    }

    @Test
    fun `timeout of second live source falls back to a complete repository snapshot`() = withFixture { fixture ->
        fixture.live = true
        fixture.timeoutPrices = true
        val repository = fixture.repository()
        repository.refresh(fixture.scope).join()
        assertNull(repository.state.value.lastError)
        assertEquals(1, fixture.requests.get())
        assertEquals("remote-1", repository.state.value.downloadedRevision)
        repository.applyDownloaded(fixture.scope).join()
        assertEquals(4.0, currentInput(), 0.0)
    }

    private fun document(revision: String, input: Double) =
        validDocument.replace("\"test\"", "\"$revision\"").replace("\"input\": 1.0", "\"input\": $input")

    private fun writeTestCache(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.scope.cancel()
            fixture.directory.deleteRecursively()
            DefaultModelPricingCollect.installCatalog(PricingCatalogJson.parse(validDocument))
        }
    }

    private inner class Fixture {
        val directory = Files.createTempDirectory("pricing-apply-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requests = AtomicInteger()
        var remote = document("remote-1", 4.0)
        var failApplication = false
        var live = false
        var timeoutPrices = false
        private val context = mock<Context>()
        private val assets = mock<AssetManager>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().url.host == "models.dev") {
                if (!live || (timeoutPrices && chain.request().url.encodedPath == "/api.json")) {
                    throw SocketTimeoutException("live timeout")
                }
                if (chain.request().url.encodedPath == "/models.json") {
                    """{"provider-a/model-a":{"name":"Model A"}}"""
                } else {
                    """{"provider-a":{"models":{"model-a":{"cost":{"input":9,"output":2,"cache_read":0.5}}}}}"""
                }
            } else {
                requests.incrementAndGet()
                remote
            }
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        init {
            whenever(context.assets).thenReturn(assets)
            whenever(context.noBackupFilesDir).thenReturn(directory)
            whenever(assets.open("pricing/official_model_pricing_v1.json"))
                .thenAnswer { ByteArrayInputStream(validDocument.toByteArray()) }
            whenever(assets.open(ModelsDevPricingCatalog.SOURCES_ASSET))
                .thenAnswer { ByteArrayInputStream("""{"provider-a":"provider-a"}""".toByteArray()) }
        }

        fun repository() = PricingCatalogRepository(
            context = context,
            httpClient = client,
            remoteUrl = "https://example.test/pricing.json",
            cacheWriter = { file, bytes ->
                if (failApplication && file.name.startsWith("applied_")) throw IOException("disk full")
                writeTestCache(file, bytes)
            },
        )
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
