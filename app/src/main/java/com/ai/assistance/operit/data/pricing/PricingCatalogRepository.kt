package com.ai.assistance.operit.data.pricing

import android.content.Context
import android.util.AtomicFile
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

enum class PricingCatalogSource { BUNDLED, APPLIED_REMOTE }

data class PricingCatalogState(
    val source: PricingCatalogSource,
    val revision: String,
    val generatedAt: String,
    val lastCheckedAt: Long?,
    val refreshing: Boolean,
    val lastError: String?,
    val downloadedRevision: String? = null,
    val downloadedGeneratedAt: String? = null,
    val applying: Boolean = false,
) {
    val canApply: Boolean
        get() = !refreshing && !applying && downloadedRevision != null &&
            (source != PricingCatalogSource.APPLIED_REMOTE || downloadedRevision != revision)
}

typealias ModelPricingCatalogStatus = PricingCatalogState

/**
 * Process-wide pricing catalog. Reads and all network work happen off the request hot path;
 * consumers only see the immutable in-memory snapshot installed by [install].
 */
class PricingCatalogRepository(
    private val context: Context,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val remoteUrl: String = defaultRemoteUrl(),
    private val cacheWriter: (File, ByteArray) -> Unit = ::writeAtomically,
    private val modelsUrl: String = ModelsDevPricingCatalog.MODELS_URL,
    private val pricesUrl: String = ModelsDevPricingCatalog.PRICES_URL,
) {
    companion object {
        const val MAIN_REMOTE_URL =
            "https://raw.githubusercontent.com/CATMIAOZHI/Operit/personal/main/" +
                "app/src/main/assets/pricing/official_model_pricing_v1.json"
        const val DEV_REMOTE_URL =
            "https://raw.githubusercontent.com/CATMIAOZHI/Operit/personal/dev/" +
                "app/src/main/assets/pricing/official_model_pricing_v1.json"
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
        private const val CACHE_DIR = "operit/pricing"
        private const val CACHE_FILE = "official_model_pricing_v1.json"
        private const val APPLIED_FILE = "applied_official_model_pricing_v1.json"
        private const val BUNDLED_PATH = "pricing/official_model_pricing_v1.json"

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        internal fun remoteUrlFor(personalDev: Boolean): String =
            if (personalDev) DEV_REMOTE_URL else MAIN_REMOTE_URL

        private fun defaultRemoteUrl(): String =
            remoteUrlFor(BuildConfig.PERSONAL_DEV_UPDATE_CHANNEL)

        private fun writeAtomically(file: File, bytes: ByteArray) {
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        }
    }

    private val lock = Mutex()
    private var downloadedDocument: PricingCatalogDocument? = null
    private val stateMutable = MutableStateFlow(loadInitial())
    val state: StateFlow<PricingCatalogState> = stateMutable.asStateFlow()
    @Volatile
    private var snapshotMutable: PricingCatalogDocument = currentDocument
    private var operationJob: Job? = null

    init {
        install(snapshotMutable)
    }

    val snapshot: PricingCatalogDocument
        get() = snapshotMutable

    fun refresh(scope: CoroutineScope): Job = startOperation(scope, apply = false)

    fun applyDownloaded(scope: CoroutineScope): Job = startOperation(scope, apply = true)

    /** A click cannot apply a different download while an operation is still running. */
    @Synchronized
    private fun startOperation(scope: CoroutineScope, apply: Boolean): Job {
        operationJob?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            if (apply) applyNow() else refreshNow()
        }
        operationJob = job
        job.invokeOnCompletion {
            synchronized(this) {
                if (operationJob === job) operationJob = null
            }
        }
        job.start()
        return job
    }

    private suspend fun refreshNow() {
        lock.withLock {
            val current = stateMutable.value
            stateMutable.value = current.copy(refreshing = true, lastError = null)
            try {
                val document = fetchRemote()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                writeCache(cacheFile(), document)
                downloadedDocument = document
                stateMutable.value = current.copy(
                    downloadedRevision = document.revision,
                    downloadedGeneratedAt = document.generatedAt,
                    lastCheckedAt = nowMillis(),
                    refreshing = false,
                    lastError = null,
                )
            } catch (error: CancellationException) {
                stateMutable.value = current.copy(refreshing = false)
                throw error
            } catch (error: Exception) {
                // Keep the last valid snapshot and only expose a diagnostic state transition.
                stateMutable.value = current.copy(
                    refreshing = false,
                    lastCheckedAt = nowMillis(),
                    lastError = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private suspend fun applyNow() {
        lock.withLock {
            val current = stateMutable.value
            if (!current.canApply) return
            val document = downloadedDocument ?: return
            stateMutable.value = current.copy(applying = true, lastError = null)
            try {
                // Persist only the explicitly selected snapshot, independently of future downloads.
                writeCache(appliedFile(), document)
                install(document)
                stateMutable.value = current.copy(
                    source = PricingCatalogSource.APPLIED_REMOTE,
                    revision = document.revision,
                    generatedAt = document.generatedAt,
                    applying = false,
                    lastError = null,
                )
            } catch (error: CancellationException) {
                stateMutable.value = current.copy(applying = false)
                throw error
            } catch (error: Exception) {
                stateMutable.value = current.copy(applying = false, lastError = error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private suspend fun fetchRemote(): PricingCatalogDocument = withContext(Dispatchers.IO) {
        try {
            val models = fetchJson(modelsUrl, MAX_SOURCE_BYTES)
            val prices = fetchJson(pricesUrl, MAX_SOURCE_BYTES)
            val sources = context.assets.open(ModelsDevPricingCatalog.SOURCES_ASSET).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            ModelsDevPricingCatalog.build(models, prices, sources)
        } catch (error: InterruptedIOException) {
            // Timeout of either live source falls back to one complete repository snapshot.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            PricingCatalogJson.parse(fetchJson(remoteUrl, MAX_RESPONSE_BYTES))
        }
    }

    private fun fetchJson(url: String, maxBytes: Int): String {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("pricing catalog HTTP ${response.code}")
            val body = response.body ?: throw IOException("pricing catalog has no response body")
            if (body.contentLength() > maxBytes) {
                throw IOException("pricing catalog response exceeds $maxBytes bytes")
            }
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw IOException("pricing catalog response too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private fun loadInitial(): PricingCatalogState {
        val bundled = loadBundled()
        // Only the official-price files can enter this index; old channel prices stay separate.
        downloadedDocument = runCatching { readCache(cacheFile()) }.getOrNull()
        val applied = runCatching { readCache(appliedFile()) }.getOrNull()
        val selected = applied ?: bundled
        currentDocument = selected
        return PricingCatalogState(
            source = if (applied != null) PricingCatalogSource.APPLIED_REMOTE else PricingCatalogSource.BUNDLED,
            revision = selected.revision,
            generatedAt = selected.generatedAt,
            lastCheckedAt = if (downloadedDocument != null) cacheLastModified() else null,
            refreshing = false,
            lastError = null,
            downloadedRevision = downloadedDocument?.revision,
            downloadedGeneratedAt = downloadedDocument?.generatedAt,
        )
    }

    private lateinit var currentDocument: PricingCatalogDocument

    private fun loadBundled(): PricingCatalogDocument = context.assets.open(BUNDLED_PATH).use {
        PricingCatalogJson.parse(it.readBytes().toString(Charsets.UTF_8))
    }

    private fun cacheFile(): File = File(context.noBackupFilesDir, "$CACHE_DIR/$CACHE_FILE")
    private fun appliedFile(): File = File(context.noBackupFilesDir, "$CACHE_DIR/$APPLIED_FILE")

    private fun cacheLastModified(): Long? = cacheFile().takeIf { it.isFile }?.lastModified()

    private fun readCache(file: File): PricingCatalogDocument {
        require(file.length() <= MAX_RESPONSE_BYTES) { "pricing cache is too large" }
        return PricingCatalogJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun writeCache(file: File, document: PricingCatalogDocument) {
        val bytes = PricingCatalogJson.strict.encodeToString(PricingCatalogDocument.serializer(), document)
        require(bytes.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) { "pricing catalog is too large" }
        cacheWriter(file, bytes.toByteArray(Charsets.UTF_8))
    }

    private fun install(document: PricingCatalogDocument) {
        snapshotMutable = document
        DefaultModelPricingCollect.installCatalog(document)
    }
}

object PricingCatalogRuntime {
    @Volatile private var repository: PricingCatalogRepository? = null
    private val repositoryMutable = MutableStateFlow<PricingCatalogRepository?>(null)
    val repositoryState: StateFlow<PricingCatalogRepository?> = repositoryMutable.asStateFlow()

    fun initialize(context: Context): PricingCatalogRepository {
        return repository ?: synchronized(this) {
            repository ?: PricingCatalogRepository(context.applicationContext).also {
                repository = it
                repositoryMutable.value = it
            }
        }
    }

    fun get(): PricingCatalogRepository? = repository
}

/** Stable application-facing entry point for status and manual refresh actions. */
object ModelPricingCatalogRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val instance: StateFlow<PricingCatalogRepository?> = PricingCatalogRuntime.repositoryState
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val status: StateFlow<PricingCatalogState?> = instance
        .flatMapLatest { repository -> repository?.state ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun getInstance(context: Context): PricingCatalogRepository =
        PricingCatalogRuntime.initialize(context)

    val snapshot: PricingCatalogDocument
        get() = requireNotNull(PricingCatalogRuntime.get()) {
            "ModelPricingCatalogRepository is not initialized"
        }.snapshot

    fun refresh(): Job? = PricingCatalogRuntime.get()?.refresh(scope)

    fun applyDownloaded(): Job? = PricingCatalogRuntime.get()?.applyDownloaded(scope)
}
