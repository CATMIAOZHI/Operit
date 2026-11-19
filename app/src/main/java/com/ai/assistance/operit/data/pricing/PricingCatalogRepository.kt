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
import kotlinx.coroutines.flow.update
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

/** How the last check in this run ended, for the card to say so. */
enum class PricingCheckOutcome { UP_TO_DATE, UPDATED, DOWNLOADED, FAILED }

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
    val lastOutcome: PricingCheckOutcome? = null,
) {
    val canApply: Boolean
        get() = !refreshing && !applying && hasNewDownload

    /** A download differs from the prices in use, whatever is running now. */
    val hasNewDownload: Boolean
        get() = downloadedRevision != null &&
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
    /** Whether the network is unmetered (Wi-Fi); called off the main thread. */
    private val unmeteredNetwork: () -> Boolean = { isUnmetered(context) },
) {
    companion object {
        const val MAIN_REMOTE_URL =
            "https://raw.githubusercontent.com/CATMIAOZHI/orbit/main/" +
                "app/src/main/assets/pricing/official_model_pricing_v1.json"
        const val DEV_REMOTE_URL =
            "https://raw.githubusercontent.com/CATMIAOZHI/orbit/dev/" +
                "app/src/main/assets/pricing/official_model_pricing_v1.json"
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
        private const val CACHE_DIR = "operit/pricing"
        private const val CACHE_FILE = "official_model_pricing_v1.json"
        private const val APPLIED_FILE = "applied_official_model_pricing_v1.json"
        private const val AUTO_FILE = "auto_update.properties"
        private const val BUNDLED_PATH = "pricing/official_model_pricing_v1.json"
        /** An automatic check runs at most this often after a successful one. */
        internal const val AUTO_INTERVAL_MS = 24 * 60 * 60_000L
        /** And no sooner than this after an attempt that failed (offline, a timeout). */
        internal const val AUTO_RETRY_MS = 60 * 60_000L

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

        /** True only on Wi-Fi or another unmetered network; anything unclear counts as metered. */
        private fun isUnmetered(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            manager != null && !manager.isActiveNetworkMetered
        }.getOrDefault(false)
    }

    private val lock = Mutex()
    private var downloadedDocument: PricingCatalogDocument? = null
    private val stateMutable = MutableStateFlow(loadInitial())
    val state: StateFlow<PricingCatalogState> = stateMutable.asStateFlow()
    @Volatile
    private var snapshotMutable: PricingCatalogDocument = currentDocument
    private var operationJob: Job? = null
    // Read once here; afterwards the settings live in memory and are only written, in the background.
    private val initialAuto = readAutoSettings()
    private val autoUpdateMutable = MutableStateFlow(initialAuto.first)
    @Volatile private var lastAttemptAt: Long? = initialAuto.second
    /** When prices were last downloaded successfully; failed checks do not move it. */
    @Volatile private var lastSuccessAt: Long? = stateMutable.value.lastCheckedAt
        .takeIf { downloadedDocument != null }
    /** Whether prices are checked daily and a new catalog applied on its own; on by default. */
    val autoUpdate: StateFlow<Boolean> = autoUpdateMutable.asStateFlow()
    private val settingSaveFailedMutable = MutableStateFlow(false)
    /** The switch could not be saved, so after a restart it may be back where it was. */
    val settingSaveFailed: StateFlow<Boolean> = settingSaveFailedMutable.asStateFlow()

    init {
        install(snapshotMutable)
    }

    val snapshot: PricingCatalogDocument
        get() = snapshotMutable

    /** A check asked for by the user; with automatic updates on, a new catalog is applied too. */
    fun refresh(scope: CoroutineScope): Job = startOperation(scope, Operation.REFRESH)

    fun applyDownloaded(scope: CoroutineScope): Job = startOperation(scope, Operation.APPLY)

    /**
     * Checks for new prices in the background when automatic updates are on and a check is due
     * (a day after the last one, an hour after a failed one), then applies what changed.
     */
    fun autoUpdateIfDue(scope: CoroutineScope): Job? {
        if (!autoUpdateMutable.value) return null
        // A download not applied yet (applying failed, or it came while the switch was off) is
        // applied now; that is only a local file, so it does not wait for the daily check.
        if (stateMutable.value.canApply) return startOperation(scope, Operation.AUTO_APPLY)
        val now = nowMillis()
        val lastChecked = lastSuccessAt
        val lastAttempt = lastAttemptAt
        // Measured either way, so a clock set back does not hold checks off for long.
        val due = (lastChecked == null || kotlin.math.abs(now - lastChecked) >= AUTO_INTERVAL_MS) &&
            (lastAttempt == null || kotlin.math.abs(now - lastAttempt) >= AUTO_RETRY_MS)
        return if (due) startOperation(scope, Operation.AUTO) else null
    }

    fun setAutoUpdate(enabled: Boolean, scope: CoroutineScope) {
        autoUpdateMutable.value = enabled
        scope.launch(Dispatchers.IO) { writeAutoSettings() }
        // Applies a catalog downloaded earlier but never applied, or runs a due check.
        if (enabled) autoUpdateIfDue(scope)
    }

    private enum class Operation { REFRESH, APPLY, AUTO, AUTO_APPLY }

    /**
     * One automatic check: whether it ended without downloading (metered network, or switched
     * off), and the single follow-up check that clicks made during it share.
     */
    private class AutoRun {
        @Volatile var skipped = false
        var handoff: Job? = null
    }

    /** The automatic check now running, if the running operation is one. */
    private var autoRun: AutoRun? = null

    /** A click cannot apply a different download while an operation is still running. */
    @Synchronized
    private fun startOperation(scope: CoroutineScope, operation: Operation): Job {
        operationJob?.takeIf { it.isActive }?.let { active ->
            val run = autoRun
            if (operation != Operation.REFRESH || run == null) return active
            // "Check now" while an automatic check is still deciding whether to download: if that
            // one ends up skipping, the check asked for runs right after, on any network. Clicks
            // during the same automatic check share that one follow-up.
            run.handoff?.let { return it }
            return scope.launch(Dispatchers.IO) {
                active.join()
                if (run.skipped) startOperation(scope, Operation.REFRESH).join()
            }.also { run.handoff = it }
        }
        val run = if (operation == Operation.AUTO) AutoRun() else null
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            when (operation) {
                Operation.APPLY -> applyNow(onlyIfAuto = false)
                Operation.AUTO_APPLY -> applyNow(onlyIfAuto = true)
                Operation.REFRESH, Operation.AUTO -> {
                    if (operation == Operation.AUTO) {
                        // The switch may have been turned off since this was asked for.
                        // The two source files are large, so automatic checks wait for an unmetered
                        // network (Wi-Fi). Asked here, off the main thread, and only once a check is due.
                        if (!autoUpdateMutable.value || !unmeteredNetwork()) {
                            run?.skipped = true
                            return@launch
                        }
                        lastAttemptAt = nowMillis()
                        writeAutoSettings()
                    }
                    // The card stays busy from the check through applying, so a switch turned on
                    // meanwhile never flashes "replace" before the prices are applied.
                    val ok = refreshNow()
                    try {
                        // The switch is read again here, so turning it off during the download keeps
                        // the new prices waiting for "replace" instead.
                        if (ok) applyNow(onlyIfAuto = true)
                    } finally {
                        stateMutable.update { it.copy(refreshing = false) }
                    }
                }
            }
        }
        operationJob = job
        autoRun = run
        job.invokeOnCompletion {
            synchronized(this) {
                if (operationJob === job) operationJob = null
            }
        }
        job.start()
        return job
    }

    /** Downloads new prices; true when that worked. The card stays busy; the caller clears it. */
    private suspend fun refreshNow(): Boolean {
        return lock.withLock {
            val current = stateMutable.value
            stateMutable.value = current.copy(refreshing = true, lastError = null)
            try {
                val document = fetchRemote()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                writeCache(cacheFile(), document)
                downloadedDocument = document
                val checkedAt = nowMillis()
                lastSuccessAt = checkedAt
                val next = current.copy(
                    downloadedRevision = document.revision,
                    downloadedGeneratedAt = document.generatedAt,
                    lastCheckedAt = checkedAt,
                    refreshing = true,
                    lastError = null,
                )
                stateMutable.value = next.copy(lastOutcome =
                    if (next.hasNewDownload) PricingCheckOutcome.DOWNLOADED else PricingCheckOutcome.UP_TO_DATE)
                true
            } catch (error: CancellationException) {
                stateMutable.value = current.copy(refreshing = false)
                throw error
            } catch (error: Exception) {
                // Keep the last valid snapshot and only expose a diagnostic state transition.
                stateMutable.value = current.copy(
                    refreshing = true,
                    lastCheckedAt = nowMillis(),
                    lastError = error.message ?: error.javaClass.simpleName,
                    lastOutcome = PricingCheckOutcome.FAILED,
                )
                false
            }
        }
    }

    /** Applies the download; with [onlyIfAuto], only while automatic updates are still on. */
    private suspend fun applyNow(onlyIfAuto: Boolean) {
        lock.withLock {
            if (onlyIfAuto && !autoUpdateMutable.value) return
            // Busy flags are ignored here: a check may still show as running while it hands over.
            val current = stateMutable.value.copy(refreshing = false)
            if (current.applying || !current.hasNewDownload) return
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
                    lastOutcome = PricingCheckOutcome.UPDATED,
                )
            } catch (error: CancellationException) {
                stateMutable.value = current.copy(applying = false)
                throw error
            } catch (error: Exception) {
                stateMutable.value = current.copy(applying = false, lastError = error.message ?: error.javaClass.simpleName,
                    lastOutcome = PricingCheckOutcome.FAILED)
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

    private fun autoFile(): File = File(context.noBackupFilesDir, "$CACHE_DIR/$AUTO_FILE")

    /**
     * Whether automatic updates are on (default yes) and when the last automatic check started.
     * A settings file that exists but cannot be read counts as off, so a switch turned off is
     * never quietly turned back on.
     */
    private fun readAutoSettings(): Pair<Boolean, Long?> {
        val file = autoFile()
        if (!file.exists()) return true to null
        return runCatching {
            val props = java.util.Properties().apply { file.inputStream().use { load(it) } }
            (props.getProperty("enabled") != "false") to props.getProperty("lastAttemptAt")?.toLongOrNull()
        }.getOrDefault(false to null)
    }

    private val settingsLock = Any()

    /**
     * Saves the switch and the last automatic attempt as they are now. Writes take turns and each
     * writes the current values, so the last one always holds the latest switch position.
     */
    private fun writeAutoSettings() = synchronized(settingsLock) {
        runCatching {
            val props = java.util.Properties()
            props.setProperty("enabled", autoUpdateMutable.value.toString())
            lastAttemptAt?.let { props.setProperty("lastAttemptAt", it.toString()) }
            val out = java.io.ByteArrayOutputStream()
            props.store(out, null)
            cacheWriter(autoFile(), out.toByteArray())
        }.let { result ->
            // The switch still works for this run; the card says it may not be kept after a restart.
            settingSaveFailedMutable.value = result.isFailure
        }
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

    fun autoUpdateIfDue(): Job? = PricingCatalogRuntime.get()?.autoUpdateIfDue(scope)

    fun setAutoUpdate(enabled: Boolean) {
        PricingCatalogRuntime.get()?.setAutoUpdate(enabled, scope)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val autoUpdate: StateFlow<Boolean> = instance
        .flatMapLatest { repository -> repository?.autoUpdate ?: flowOf(true) }
        .stateIn(scope, SharingStarted.Eagerly, true)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val settingSaveFailed: StateFlow<Boolean> = instance
        .flatMapLatest { repository -> repository?.settingSaveFailed ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)
}
