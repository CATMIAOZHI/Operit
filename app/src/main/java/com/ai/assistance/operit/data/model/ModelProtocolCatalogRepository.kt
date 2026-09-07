package com.ai.assistance.operit.data.model

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class ModelProtocolCatalogRepository(private val context: Context) {
    data class Result(val catalog: ModelProtocolCatalog, val usedLocalCopy: Boolean)

    suspend fun refreshOrLoad(): Result = withContext(Dispatchers.IO) {
        val file = File(context.noBackupFilesDir, "operit/model_catalog/model_protocols_v1.json")
        val fresh = runCatching {
            val bytes = client.newCall(
                Request.Builder().url(SOURCE_URL).get().build()
            ).execute().use { response ->
                check(response.isSuccessful) { "Model protocol catalog HTTP ${response.code}" }
                val source = checkNotNull(response.body).source()
                check(!source.request(MAX_BYTES + 1)) { "Model protocol catalog is too large" }
                source.readByteArray()
            }
            currentCoroutineContext().ensureActive()
            val catalog = ModelProtocolCatalog.parse(bytes.toString(Charsets.UTF_8))
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
            catalog
        }.getOrNull()
        currentCoroutineContext().ensureActive()
        if (fresh != null) return@withContext Result(fresh, false)
        val cached = runCatching {
            check(file.isFile && file.length() <= MAX_BYTES)
            ModelProtocolCatalog.parse(file.readText())
        }.getOrNull()
        val catalog = cached ?: context.assets.open("model_catalog/model_protocols_v1.json").use {
            ModelProtocolCatalog.parse(it.readBytes().toString(Charsets.UTF_8))
        }
        Result(catalog, true)
    }

    companion object {
        const val SOURCE_URL = "https://models.dev/api.json"
        private const val MAX_BYTES = 10L * 1024 * 1024
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
