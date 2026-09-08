package com.ai.assistance.operit.data.model

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class ModelProtocolCatalogRepository(
    private val context: Context,
    private val httpClient: OkHttpClient = client,
    private val cacheWriter: (File, ByteArray) -> Unit = ::writeAtomically,
) {
    private val lock = Mutex()
    private val updatedAtMutable = MutableStateFlow<Long?>(null)
    val updatedAt = updatedAtMutable.asStateFlow()

    /** Applying protocols never refreshes the directory or requires network access. */
    suspend fun loadCatalog(): ModelProtocolCatalog = withContext(Dispatchers.IO) {
        lock.withLock {
            val file = cacheFile()
            val cached = runCatching {
                check(file.isFile && file.length() <= MAX_BYTES)
                ModelProtocolCatalog.parse(file.readText(Charsets.UTF_8))
            }.getOrNull()
            updatedAtMutable.value = if (cached != null) file.lastModified() else null
            cached ?: context.assets.open(BUNDLED_PATH).use {
                ModelProtocolCatalog.parse(it.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    /** A failed manual refresh leaves the previously usable directory and timestamp intact. */
    suspend fun refreshCatalog(): ModelProtocolCatalog = withContext(Dispatchers.IO) {
        lock.withLock {
            val bytes = httpClient.newCall(
                Request.Builder().url(SOURCE_URL).get().build()
            ).execute().use { response ->
                check(response.isSuccessful) { "Model protocol catalog HTTP ${response.code}" }
                val source = checkNotNull(response.body).source()
                check(!source.request(MAX_BYTES + 1)) { "Model protocol catalog is too large" }
                source.readByteArray()
            }
            currentCoroutineContext().ensureActive()
            val catalog = ModelProtocolCatalog.parse(bytes.toString(Charsets.UTF_8))
            currentCoroutineContext().ensureActive()
            val file = cacheFile()
            cacheWriter(file, bytes)
            updatedAtMutable.value = file.lastModified()
            catalog
        }
    }

    private fun cacheFile() =
        File(context.noBackupFilesDir, "operit/model_catalog/model_protocols_v1.json")

    companion object {
        const val SOURCE_URL = "https://models.dev/api.json"
        private const val BUNDLED_PATH = "model_catalog/model_protocols_v1.json"
        private const val MAX_BYTES = 10L * 1024 * 1024
        private fun writeAtomically(file: File, bytes: ByteArray) {
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
        }

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
