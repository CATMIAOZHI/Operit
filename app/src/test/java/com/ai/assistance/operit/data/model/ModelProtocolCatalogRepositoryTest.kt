package com.ai.assistance.operit.data.model

import android.content.Context
import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ModelProtocolCatalogRepositoryTest {
    private val endpoint = "https://example.test/v1/chat/completions"
    private fun catalog(sdk: String) =
        """{"vendor":{"api":"https://example.test/v1","npm":"$sdk","models":{"model":{}}}}"""

    @Test
    fun localConfigurationNeedsNoNetworkAndRefreshIsExplicit() = runBlocking {
        val directory = Files.createTempDirectory("protocol-local-test").toFile()
        val context = mock<Context>()
        val assets = mock<AssetManager>()
        whenever(context.noBackupFilesDir).thenReturn(directory)
        whenever(context.assets).thenReturn(assets)
        whenever(assets.open("model_catalog/model_protocols_v1.json")).thenAnswer {
            ByteArrayInputStream(catalog("@ai-sdk/deepseek").toByteArray())
        }
        var requests = 0
        var failRefresh = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            if (failRefresh) throw IOException("test refresh failure")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(catalog("@ai-sdk/anthropic").toResponseBody()).build()
        }.build()
        val repository = ModelProtocolCatalogRepository(
            context, client,
            cacheWriter = { file, bytes -> file.parentFile?.mkdirs(); file.writeBytes(bytes) },
        )
        fun ModelProtocolCatalog.selected() = matchAll(endpoint, listOf("model"))["model"]?.protocol
        try {
            assertEquals(ModelProtocol.DEEPSEEK, repository.loadCatalog().selected())
            assertNull(repository.updatedAt.value)
            assertEquals(0, requests)

            assertEquals(ModelProtocol.ANTHROPIC, repository.refreshCatalog().selected())
            val updatedAt = repository.updatedAt.value
            assertNotNull(updatedAt)
            assertTrue(updatedAt!! > 0)
            val reopened = ModelProtocolCatalogRepository(context, client)
            assertEquals(ModelProtocol.ANTHROPIC, reopened.loadCatalog().selected())
            assertEquals(updatedAt, reopened.updatedAt.value)
            assertEquals(1, requests)

            failRefresh = true
            try {
                repository.refreshCatalog()
                fail("refresh failure must be reported")
            } catch (_: IOException) {
                // The UI reports the failure, while later local configuration remains available.
            }
            assertEquals(ModelProtocol.ANTHROPIC, repository.loadCatalog().selected())
            assertEquals(updatedAt, repository.updatedAt.value)
            assertEquals(2, requests)
        } finally {
            directory.deleteRecursively()
        }
    }
}
