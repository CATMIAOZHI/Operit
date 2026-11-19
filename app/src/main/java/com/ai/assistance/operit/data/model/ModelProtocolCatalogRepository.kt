package com.ai.assistance.operit.data.model

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
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
    // Shared by every instance on the same cache file: a background refresh reaches an open
    // settings page, and the directory (several MB once refreshed) is parsed once per process.
    private val shared = sharedStates.getOrPut(cacheFile().absolutePath) { SharedState() }
    private val lock get() = shared.lock
    private val updatedAtMutable get() = shared.updatedAt
    val updatedAt = shared.updatedAt.asStateFlow()

    /** Applying protocols never refreshes the directory or requires network access. */
    suspend fun loadCatalog(): ModelProtocolCatalog {
        shared.catalog?.let { return it }
        return withContext(Dispatchers.IO) {
            lock.withLock {
                shared.catalog ?: readLocal().let { (json, parsed) -> parsed ?: ModelProtocolCatalog.parse(json) }
                    .also { shared.catalog = it }
            }
        }
    }

    /** Shared local directory for protocol matching and provider model selection. */
    suspend fun loadCatalogJson(): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val (json, parsed) = readLocal()
            // Parsed only when nothing has been yet; the parsed directory is kept for matching.
            if (shared.catalog == null) shared.catalog = parsed ?: ModelProtocolCatalog.parse(json)
            json
        }
    }

    /**
     * The refreshed copy when it is usable, else the bundled one. While no parsed directory is
     * held, the refreshed copy is checked by parsing it, and that parsed form comes back too.
     */
    private fun readLocal(): Pair<String, ModelProtocolCatalog?> {
        val file = cacheFile()
        val cached = runCatching {
            check(file.isFile && file.length() <= MAX_BYTES)
            val json = file.readText(Charsets.UTF_8)
            json to (if (shared.catalog == null) ModelProtocolCatalog.parse(json) else null)
        }.getOrNull()
        updatedAtMutable.value = if (cached != null) file.lastModified() else null
        return cached ?: (context.assets.open(BUNDLED_PATH).use {
            it.readBytes().toString(Charsets.UTF_8)
        } to null)
    }

    /**
     * A failed refresh leaves the previously usable directory and timestamp intact. The download
     * is cut down as it arrives, one provider at a time, and local reads are not held up by it;
     * refreshes run one at a time, and one that waited for another takes that one's result.
     */
    suspend fun refreshCatalog(): ModelProtocolCatalog = withContext(Dispatchers.IO) {
        val seen = shared.refreshes
        shared.refreshLock.withLock {
            if (shared.refreshes != seen) shared.catalog?.let { return@withLock it }
            val slim = httpClient.newCall(
                Request.Builder().url(SOURCE_URL).get().build()
            ).execute().use { response ->
                check(response.isSuccessful) { "Model protocol catalog HTTP ${response.code}" }
                val body = checkNotNull(response.body)
                check(body.contentLength() <= MAX_DOWNLOAD_BYTES) { "Model protocol catalog is too large" }
                LimitedInputStream(body.byteStream(), MAX_DOWNLOAD_BYTES).reader(Charsets.UTF_8).use {
                    ModelProtocolCatalog.slim(it)
                }
            }
            currentCoroutineContext().ensureActive()
            val catalog = ModelProtocolCatalog.parse(slim)
            val slimBytes = slim.toByteArray(Charsets.UTF_8)
            check(slimBytes.size <= MAX_BYTES) { "Model protocol catalog is too large" }
            currentCoroutineContext().ensureActive()
            lock.withLock {
                val file = cacheFile()
                cacheWriter(file, slimBytes)
                updatedAtMutable.value = file.lastModified()
                shared.catalog = catalog
            }
            shared.refreshes++
            catalog
        }
    }

    /** Fails the read once more than [max] bytes have come through. */
    private class LimitedInputStream(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) counted(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) counted(it.toLong()) }

        private fun counted(bytes: Long) {
            count += bytes
            if (count > max) throw IOException("Model protocol catalog is too large")
        }
    }

    /** When the refreshed copy was written, or null with none; read without parsing it. */
    fun localVersion(): Long? = cacheFile().takeIf { it.isFile }?.lastModified()

    private fun cacheFile() =
        File(context.noBackupFilesDir, "operit/model_catalog/model_protocols_v1.json")

    companion object {
        private class SharedState {
            val lock = Mutex()
            val refreshLock = Mutex()
            @Volatile
            var refreshes = 0
            val updatedAt = MutableStateFlow<Long?>(null)
            @Volatile
            var catalog: ModelProtocolCatalog? = null
        }

        private val sharedStates = ConcurrentHashMap<String, SharedState>()

        const val SOURCE_URL = "https://models.dev/api.json"
        private const val BUNDLED_PATH = "model_catalog/model_protocols_v1.json"
        private const val MAX_BYTES = 10L * 1024 * 1024
        /** The full download, before it is cut down to [MAX_BYTES] at most. */
        private const val MAX_DOWNLOAD_BYTES = 40L * 1024 * 1024
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
