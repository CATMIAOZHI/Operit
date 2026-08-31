package com.ai.assistance.operit.data.pricing

import android.content.Context
import android.util.AtomicFile
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit

enum class PricingCatalogSource { BUNDLED, CACHED_REMOTE }

data class PricingCatalogState(
    val source: PricingCatalogSource,
    val revision: String,
    val generatedAt: String,
    val lastCheckedAt: Long?,
    val refreshing: Boolean,
    val lastError: String?,
)

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
) {
    companion object {
        const val MAIN_REMOTE_URL =
            "https://raw.githubusercontent.com/CATMIAOZHI/Operit/personal/main/" +
                "app/src/main/assets/pricing/model_pricing_v1.json"
        const val DEV_REMOTE_URL =
            "https://raw.githubusercontent.com/CATMIAOZHI/Operit/personal/dev/" +
                "app/src/main/assets/pricing/model_pricing_v1.json"
        const val TTL_MILLIS = 24L * 60L * 60L * 1000L
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val CACHE_DIR = "operit/pricing"
        private const val CACHE_FILE = "model_pricing_v1.json"
        private const val BUNDLED_PATH = "pricing/model_pricing_v1.json"

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
    }

    private val lock = Mutex()
    private val stateMutable = MutableStateFlow(loadInitial())
    val state: StateFlow<PricingCatalogState> = stateMutable.asStateFlow()
    @Volatile
    private var snapshotMutable: PricingCatalogDocument = currentDocument
    private var refreshJob: Job? = null

    init {
        install(snapshotMutable)
    }

    val snapshot: PricingCatalogDocument
        get() = snapshotMutable

    fun refreshIfStale(scope: CoroutineScope): Job? {
        val checked = stateMutable.value.lastCheckedAt
        if (checked != null && nowMillis() - checked < TTL_MILLIS) return null
        return refresh(scope, force = false)
    }

    /** Coalesces startup and manual refreshes into one active download. */
    @Synchronized
    fun refresh(scope: CoroutineScope, force: Boolean = false): Job {
        refreshJob?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { refreshNow(force) }
        refreshJob = job
        job.invokeOnCompletion {
            synchronized(this) {
                if (refreshJob === job) refreshJob = null
            }
        }
        job.start()
        return job
    }

    private suspend fun refreshNow(force: Boolean) {
        lock.withLock {
            val current = stateMutable.value
            if (!force && current.lastCheckedAt != null && nowMillis() - current.lastCheckedAt < TTL_MILLIS) {
                return
            }
            stateMutable.value = current.copy(refreshing = true, lastError = null)
            try {
                val document = fetchRemote()
                writeCache(document)
                install(document)
                stateMutable.value = PricingCatalogState(
                    source = PricingCatalogSource.CACHED_REMOTE,
                    revision = document.revision,
                    generatedAt = document.generatedAt,
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

    private suspend fun fetchRemote(): PricingCatalogDocument = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(remoteUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("pricing catalog HTTP ${response.code}")
            val body = response.body ?: throw IOException("pricing catalog has no response body")
            if (body.contentLength() > MAX_RESPONSE_BYTES) {
                throw IOException("pricing catalog response exceeds $MAX_RESPONSE_BYTES bytes")
            }
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) throw IOException("pricing catalog response too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            PricingCatalogJson.parse(bytes.toString(Charsets.UTF_8))
        }
    }

    private fun loadInitial(): PricingCatalogState {
        val bundled = loadBundled()
        val cached = runCatching { readCache() }.getOrNull()
        val selected = cached ?: bundled
        currentDocument = selected
        return PricingCatalogState(
            source = if (cached != null) PricingCatalogSource.CACHED_REMOTE else PricingCatalogSource.BUNDLED,
            revision = selected.revision,
            generatedAt = selected.generatedAt,
            lastCheckedAt = if (cached != null) cacheLastModified() else null,
            refreshing = false,
            lastError = null,
        )
    }

    private lateinit var currentDocument: PricingCatalogDocument

    private fun loadBundled(): PricingCatalogDocument = context.assets.open(BUNDLED_PATH).use {
        PricingCatalogJson.parse(it.readBytes().toString(Charsets.UTF_8))
    }

    private fun cacheFile(): File = File(context.noBackupFilesDir, "$CACHE_DIR/$CACHE_FILE")

    private fun cacheLastModified(): Long? = cacheFile().takeIf { it.isFile }?.lastModified()

    private fun readCache(): PricingCatalogDocument {
        val file = cacheFile()
        require(file.length() <= MAX_RESPONSE_BYTES) { "pricing cache is too large" }
        return PricingCatalogJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun writeCache(document: PricingCatalogDocument) {
        val file = cacheFile()
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val bytes = PricingCatalogJson.strict.encodeToString(PricingCatalogDocument.serializer(), document)
        require(bytes.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) { "pricing catalog is too large" }
        val stream = atomic.startWrite()
        try {
            stream.write(bytes.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
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

    fun initialize(context: Context, scope: CoroutineScope): PricingCatalogRepository {
        return repository ?: synchronized(this) {
            repository ?: PricingCatalogRepository(context.applicationContext).also {
                repository = it
                repositoryMutable.value = it
                it.refreshIfStale(scope)
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
        PricingCatalogRuntime.initialize(context, scope)

    val snapshot: PricingCatalogDocument
        get() = requireNotNull(PricingCatalogRuntime.get()) {
            "ModelPricingCatalogRepository is not initialized"
        }.snapshot

    fun refresh(force: Boolean = true): Job? = PricingCatalogRuntime.get()?.refresh(scope, force)
}
