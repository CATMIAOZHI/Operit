package com.ai.assistance.operit.data.model

import android.content.Context
import android.util.AtomicFile
import com.ai.assistance.operit.BuildConfig
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
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

class OfficialModelCapabilitiesRepository(
    private val context: Context,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val sourceUrl: String = LIVE_URL,
    private val cacheWriter: (File, ByteArray) -> Unit = ::writeAtomically,
    private val fallbackUrl: String = defaultRemoteUrl(),
) {
    private val lock = Mutex()
    private val updatedAtMutable = MutableStateFlow<Long?>(null)
    val updatedAt = updatedAtMutable.asStateFlow()
    @Volatile
    private var inMemoryCatalog: OfficialModelCapabilitiesCatalog? = null

    suspend fun loadCatalog(): OfficialModelCapabilitiesCatalog =
        withContext(Dispatchers.IO) {
            inMemoryCatalog ?: lock.withLock {
                inMemoryCatalog ?: loadLocalCatalog().also { inMemoryCatalog = it }
            }
        }

    suspend fun refreshCatalog(): OfficialModelCapabilitiesCatalog =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val bytes = try {
                    fetchRemoteBytes(sourceUrl)
                } catch (error: InterruptedIOException) {
                    currentCoroutineContext().ensureActive()
                    fetchRemoteBytes(fallbackUrl)
                }
                val catalog =
                    OfficialModelCapabilitiesCatalog.parse(
                        bytes.toString(Charsets.UTF_8)
                    )
                currentCoroutineContext().ensureActive()
                writeCache(bytes)
                inMemoryCatalog = catalog
                updatedAtMutable.value = cacheFile().lastModified()
                catalog
            }
        }

    private fun loadLocalCatalog(): OfficialModelCapabilitiesCatalog {
        val cached =
            runCatching {
                val file = cacheFile()
                require(file.isFile) { "Model capability cache does not exist" }
                require(file.length() <= MAX_RESPONSE_BYTES) {
                    "Model capability cache exceeds the size limit"
                }
                OfficialModelCapabilitiesCatalog.parse(file.readText(Charsets.UTF_8))
            }.getOrNull()
        updatedAtMutable.value = if (cached != null) cacheFile().lastModified() else null
        return cached ?: context.assets.open(BUNDLED_PATH).use { input ->
            OfficialModelCapabilitiesCatalog.parse(
                input.readBytes().toString(Charsets.UTF_8)
            )
        }
    }

    private fun fetchRemoteBytes(url: String): ByteArray {
        val request =
            Request.Builder()
                .url(url)
                .get()
                .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    "Official model catalog request failed: HTTP ${response.code}"
                )
            }
            val body = response.body ?: throw IOException("Official model catalog is empty")
            val contentLength = body.contentLength()
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw IOException("Official model catalog exceeds the size limit")
            }
            val source = body.source()
            if (source.request(MAX_RESPONSE_BYTES + 1L)) {
                throw IOException("Official model catalog exceeds the size limit")
            }
            return source.readByteArray()
        }
    }

    private fun writeCache(bytes: ByteArray) {
        require(bytes.size <= MAX_RESPONSE_BYTES) {
            "Official model catalog exceeds the size limit"
        }
        val file = cacheFile()
        cacheWriter(file, bytes)
    }

    private fun cacheFile(): File =
        File(context.noBackupFilesDir, "$CACHE_DIR/$CACHE_FILE")

    companion object {
        const val LIVE_URL = "https://models.dev/models.json"
        const val MAIN_REMOTE_URL =
            "https://raw.githubusercontent.com/CATMIAOZHI/Operit/personal/main/" +
                "app/src/main/assets/model_catalog/model_capabilities_v1.json"
        const val DEV_REMOTE_URL =
            "https://raw.githubusercontent.com/CATMIAOZHI/Operit/personal/dev/" +
                "app/src/main/assets/model_catalog/model_capabilities_v1.json"
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val CACHE_DIR = "operit/model_catalog"
        private const val CACHE_FILE = "model_capabilities_v1.json"
        private const val BUNDLED_PATH =
            "model_catalog/model_capabilities_v1.json"

        internal fun remoteUrlFor(personalDev: Boolean): String =
            if (personalDev) DEV_REMOTE_URL else MAIN_REMOTE_URL

        private fun defaultRemoteUrl(): String =
            remoteUrlFor(BuildConfig.PERSONAL_DEV_UPDATE_CHANNEL)

        private fun writeAtomically(file: File, bytes: ByteArray) {
            file.parentFile?.mkdirs()
            val atomicFile = AtomicFile(file)
            val stream = atomicFile.startWrite()
            try {
                stream.write(bytes)
                atomicFile.finishWrite(stream)
            } catch (error: Throwable) {
                atomicFile.failWrite(stream)
                throw error
            }
        }

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}
