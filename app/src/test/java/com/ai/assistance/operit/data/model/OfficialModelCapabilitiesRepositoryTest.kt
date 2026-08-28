package com.ai.assistance.operit.data.model

import android.content.Context
import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class OfficialModelCapabilitiesRepositoryTest {
    @Test
    fun `dev and stable builds use matching repository branches`() {
        assertEquals(
            OfficialModelCapabilitiesRepository.DEV_REMOTE_URL,
            OfficialModelCapabilitiesRepository.remoteUrlFor(personalDev = true),
        )
        assertEquals(
            OfficialModelCapabilitiesRepository.MAIN_REMOTE_URL,
            OfficialModelCapabilitiesRepository.remoteUrlFor(personalDev = false),
        )
    }

    @Test
    fun `first local load uses bundled catalog without network`() {
        val tempDir = Files.createTempDirectory("model-capabilities-bundled-test").toFile()
        val context = mock<Context>()
        val assets = mock<AssetManager>()
        whenever(context.assets).thenReturn(assets)
        whenever(context.noBackupFilesDir).thenReturn(tempDir)
        whenever(assets.open("model_catalog/model_capabilities_v1.json"))
            .thenAnswer { ByteArrayInputStream(bundledCatalog.toByteArray()) }

        try {
            val catalog =
                runBlocking {
                    OfficialModelCapabilitiesRepository(
                        context = context,
                        httpClient =
                            OkHttpClient.Builder()
                                .addInterceptor {
                                    throw AssertionError("local load must not use the network")
                                }
                                .build(),
                        sourceUrl = "https://example.test/model-capabilities.json",
                    ).loadCatalog()
                }
            assertEquals("vendor/bundled", catalog.models.single().officialModelId)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `manual refresh persists one download for later local loads`() {
        val tempDir = Files.createTempDirectory("model-capabilities-test").toFile()
        val context = mock<Context>()
        val assets = mock<AssetManager>()
        whenever(context.assets).thenReturn(assets)
        whenever(context.noBackupFilesDir).thenReturn(tempDir)
        whenever(assets.open("model_catalog/model_capabilities_v1.json"))
            .thenAnswer { ByteArrayInputStream(bundledCatalog.toByteArray()) }

        val requests = AtomicInteger(0)
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            refreshedCatalog.toResponseBody(
                                "application/json".toMediaType()
                            )
                        )
                        .build()
                }
                .build()

        try {
            val refreshed =
                runBlocking {
                    OfficialModelCapabilitiesRepository(
                        context = context,
                        httpClient = client,
                        sourceUrl = "https://example.test/model-capabilities.json",
                        cacheWriter = { file, bytes ->
                            file.parentFile?.mkdirs()
                            file.writeBytes(bytes)
                        },
                    ).refreshCatalog()
                }
            assertEquals("vendor/refreshed", refreshed.models.single().officialModelId)
            assertEquals(1, requests.get())

            val loadedFromCache =
                runBlocking {
                    OfficialModelCapabilitiesRepository(
                        context = context,
                        httpClient =
                            OkHttpClient.Builder()
                                .addInterceptor {
                                    throw AssertionError("local load must not use the network")
                                }
                                .build(),
                        sourceUrl = "https://example.test/model-capabilities.json",
                    ).loadCatalog()
                }
            assertEquals(
                "vendor/refreshed",
                loadedFromCache.models.single().officialModelId,
            )
            assertTrue(
                tempDir.resolve(
                    "operit/model_catalog/model_capabilities_v1.json"
                ).isFile
            )
            assertEquals(1, requests.get())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `failed cache replacement keeps the previous local catalog`() {
        val tempDir = Files.createTempDirectory("model-capabilities-failure-test").toFile()
        val cacheFile =
            tempDir.resolve("operit/model_catalog/model_capabilities_v1.json")
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeText(bundledCatalog)
        val context = mock<Context>()
        val assets = mock<AssetManager>()
        whenever(context.assets).thenReturn(assets)
        whenever(context.noBackupFilesDir).thenReturn(tempDir)
        whenever(assets.open("model_catalog/model_capabilities_v1.json"))
            .thenAnswer { ByteArrayInputStream(bundledCatalog.toByteArray()) }
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            refreshedCatalog.toResponseBody(
                                "application/json".toMediaType()
                            )
                        )
                        .build()
                }
                .build()

        try {
            try {
                runBlocking {
                    OfficialModelCapabilitiesRepository(
                        context = context,
                        httpClient = client,
                        sourceUrl = "https://example.test/model-capabilities.json",
                        cacheWriter = { _, _ -> throw IOException("disk full") },
                    ).refreshCatalog()
                }
                throw AssertionError("refresh should fail when cache replacement fails")
            } catch (error: IOException) {
                assertEquals("disk full", error.message)
            }

            val retained =
                runBlocking {
                    OfficialModelCapabilitiesRepository(
                        context = context,
                        httpClient = client,
                        sourceUrl = "https://example.test/model-capabilities.json",
                    ).loadCatalog()
                }
            assertEquals("vendor/bundled", retained.models.single().officialModelId)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private val bundledCatalog =
        """
        {
          "vendor/bundled": {
            "name": "Bundled",
            "family": "test",
            "modalities": {"input": ["text"]}
          }
        }
        """.trimIndent()

    private val refreshedCatalog =
        """
        {
          "vendor/refreshed": {
            "name": "Refreshed",
            "family": "test",
            "modalities": {"input": ["text", "image"]}
          }
        }
        """.trimIndent()
}
