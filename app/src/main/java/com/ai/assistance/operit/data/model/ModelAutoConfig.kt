package com.ai.assistance.operit.data.model

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.api.chat.llmprovider.OpenCodeZenFree
import com.ai.assistance.operit.api.chat.llmprovider.ThinkingRequestSemantics
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fills in what the local model catalogs know about a configuration's models: the request protocol
 * (and with it the thinking levels a request may send), whether a model reads pictures, audio or
 * video, and how long its context window and replies can be.
 *
 * Automatic matching only fills gaps, each kind on its own: a model with a protocol keeps it, a model
 * with limits keeps them, and a model whose inputs are settled (see
 * [ModelConfigData.settledCapabilityModels]) keeps those. "Re-match" in the model settings replaces
 * everything the catalogs know, keeping only a model's own API address.
 */
object ModelAutoConfig {
    private const val TAG = "ModelAutoConfig"
    private const val PREFS = "model_auto_config"
    private const val APPLIED = "catalogs_applied_version"
    private const val CAPABILITIES = "capabilities"
    private const val PROTOCOLS = "protocols"
    private const val REFRESH_EVERY_MS = 7L * 24 * 60 * 60 * 1000
    private const val RETRY_AFTER_MS = 24L * 60 * 60 * 1000
    private const val WORK_NAME = "model_catalog_refresh"
    private val refreshing = Mutex()
    /** Successful downloads of each catalog in this process, counted under [refreshing]. */
    private val refreshed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** One background check fills in at a time; re-matching never waits for it. */
    private val applying = Mutex()

    /** The local catalogs; one that could not be read is null and matches nothing. */
    class Catalogs(
        val protocols: ModelProtocolCatalog?,
        val capabilities: OfficialModelCapabilitiesCatalog?,
    )

    /** What matching changes, per model: only the entries to write. */
    data class Plan(
        val protocols: Map<String, ModelProtocolSettings>,
        val capabilities: Map<String, ModelMultimodalCapabilities>,
        val limits: Map<String, ModelLimits>,
        /** Models whose inputs now come from the catalog, changed or not. */
        val settled: Set<String>,
        /** Models found in a catalog with something to apply, changed or not. */
        val matched: Set<String>,
        /** Models the catalog says read pictures, changed or not. */
        val images: Set<String> = emptySet(),
    ) {
        val isEmpty get() = protocols.isEmpty() && capabilities.isEmpty() && limits.isEmpty() && settled.isEmpty()
    }

    data class Refresh(val capabilities: Boolean, val protocols: Boolean) {
        val all get() = capabilities && protocols
    }

    /**
     * What re-matching did to a configuration; [kept] lists the models changed meanwhile (or with
     * edits not saved yet), which were left as they are.
     */
    class Readapt(val config: ModelConfigData, val plan: Plan, val refresh: Refresh, val kept: Set<String>)

    /** What the catalogs hold for [names] under the given provider and address. */
    private class Found(
        val protocols: Map<String, ModelProtocolSettings>,
        val capabilities: Map<String, ModelMultimodalCapabilities>,
        val limits: Map<String, ModelLimits>,
    ) {
        val matched get() = protocols.keys + capabilities.keys
    }

    /** The local catalogs, bundled or last refreshed; never touches the network. */
    suspend fun loadCatalogs(context: Context): Catalogs {
        val app = context.applicationContext
        suspend fun <T> read(what: String, block: suspend () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not read the $what catalog: ${e.message}")
            null
        }
        return Catalogs(
            protocols = read("protocol") { ModelProtocolCatalogRepository(app).loadCatalog() },
            capabilities = read("capability") { OfficialModelCapabilitiesRepository(app).loadCatalog() },
        )
    }

    private fun find(providerTypeId: String, endpoint: String, names: List<String>, catalogs: Catalogs): Found {
        val provider = ApiProviderType.fromProviderTypeId(providerTypeId)
        // OpenCode Zen Free has no protocol choice in the settings, yet its models' entries (the
        // thinking levels a request may send) come from the catalog under its own chat address.
        val zenFree = provider == ApiProviderType.OPENCODE_ZEN_FREE
        val protocols = catalogs.protocols
            ?.takeIf { zenFree || supportsModelProtocolOverrides(providerTypeId) }
            ?.matchAll(if (zenFree) OpenCodeZenFree.CHAT_ENDPOINT else endpoint, names)
            .orEmpty()
            // An entry that keeps the provider's protocol only brings thinking levels; it is kept
            // only where the provider's requests send them (not Anthropic, Gemini or OpenRouter).
            .filterValues {
                it.protocol != ModelProtocol.INHERIT ||
                    (!it.reasoningEfforts.isNullOrEmpty() && ThinkingRequestSemantics.readsCatalogEfforts(provider))
            }
        val entries = catalogs.capabilities?.matchEntries(names).orEmpty()
        val capabilities = when {
            catalogs.capabilities == null -> emptyMap()
            zenFree -> OpenCodeZenFree.matchMultimodalCapabilities(catalogs.capabilities, names)
            else -> entries.mapValues { it.value.capabilities }
        }
        val limits = entries.mapNotNull { (name, entry) -> entry.limits?.let { name to it } }.toMap()
        return Found(protocols, capabilities, limits)
    }

    /** The models of [names] the catalogs have something for; for the settings page's notes. */
    fun matched(providerTypeId: String, endpoint: String, names: List<String>, catalogs: Catalogs): Set<String> =
        find(providerTypeId, endpoint, names, catalogs).matched

    /**
     * Works out what the catalogs change for [names] of [config]. With [force] the catalogs' word
     * replaces what is set; otherwise only gaps are filled (see the class comment).
     */
    fun plan(config: ModelConfigData, names: List<String>, catalogs: Catalogs, force: Boolean): Plan {
        val found = find(config.apiProviderTypeId, config.apiEndpoint, names, catalogs)
        val zenFree = ApiProviderType.fromProviderTypeId(config.apiProviderTypeId) == ApiProviderType.OPENCODE_ZEN_FREE
        val protocols = found.protocols.mapNotNull { (name, catalog) ->
            val had = config.modelProtocolSettings[name]
            val settings = when {
                // Zen Free's entries cannot be set by hand, so the newest catalog always wins.
                zenFree -> catalog
                force -> keepOwnAddress(had, catalog)
                had == null -> catalog
                else -> return@mapNotNull null
            }
            if (settings == had) null else name to settings
        }.toMap()
        val settled = mutableSetOf<String>()
        val capabilities = mutableMapOf<String, ModelMultimodalCapabilities>()
        val alreadySettled = if (force) emptySet() else config.settledModels()
        found.capabilities.forEach { (name, catalog) ->
            if (name in alreadySettled) return@forEach
            settled += name
            if (catalog != config.multimodalCapabilitiesForModel(name)) capabilities[name] = catalog
        }
        val limits = found.limits.filter { (name, catalog) ->
            (force || name !in config.modelLimits) && config.modelLimits[name] != catalog
        }
        return Plan(protocols, capabilities, limits, settled, found.matched,
            found.capabilities.filterValues { it.image }.keys)
    }

    /** A model's own API address survives re-matching, and so does the protocol it needs. */
    private fun keepOwnAddress(had: ModelProtocolSettings?, catalog: ModelProtocolSettings): ModelProtocolSettings {
        val address = had?.endpoint?.takeIf { it.isNotBlank() } ?: return catalog
        // An inherited protocol ignores a model address, so the one that used it stays.
        val protocol = if (catalog.protocol == ModelProtocol.INHERIT) had.protocol else catalog.protocol
        return catalog.copy(protocol = protocol, endpoint = address)
    }

    /** [plan] written into this configuration. */
    fun ModelConfigData.withPlan(plan: Plan): ModelConfigData {
        if (plan.isEmpty) return this
        val listed = getModelList(modelName)
        val listedSet = listed.toSet()
        return copy(
            modelProtocolSettings = modelProtocolSettings + plan.protocols.filterKeys { it in listedSet },
            modelMultimodalCapabilities = if (plan.capabilities.isEmpty()) modelMultimodalCapabilities
            else listed.associateWith { name -> plan.capabilities[name] ?: multimodalCapabilitiesForModel(name) },
            modelLimits = modelLimits + plan.limits.filterKeys { it in listedSet },
            settledCapabilityModels = (settledModels() + plan.settled).filterTo(mutableSetOf()) { it in listedSet },
        )
    }

    /** This configuration with every gap the local catalogs know of filled in. */
    fun ModelConfigData.filled(catalogs: Catalogs): ModelConfigData =
        withPlan(plan(this, getModelList(modelName), catalogs, force = false))

    /** What [fill] did: models given a protocol, and models the catalog says read pictures. */
    data class Result(val protocols: List<String>, val images: List<String>)

    /**
     * Fills in [configId] from the local catalogs, for [models] only when given (else every model
     * in it), in one edit; never touches the network.
     */
    suspend fun fill(
        context: Context,
        configId: String,
        models: Collection<String>? = null,
        catalogs: Catalogs? = null,
        /** False once [catalogs] are no longer the ones in use: then nothing is written. */
        current: () -> Boolean = { true },
    ): Result {
        val app = context.applicationContext
        val configs = ModelConfigManager(app)
        fun names(config: ModelConfigData) = getModelList(config.modelName).filter { models == null || it in models }
        val stored = configs.getModelConfig(configId) ?: return Result(emptyList(), emptyList())
        val loaded = catalogs ?: loadCatalogs(app)
        var plan = plan(stored, names(stored), loaded, force = false)
        if (!plan.isEmpty) {
            // Worked out again on the stored value, so a change made meanwhile is kept.
            val first = plan
            configs.editModelConfig(configId) { now ->
                if (!current()) return@editModelConfig now
                plan = if (now == stored) first else plan(now, names(now), loaded, force = false)
                now.withPlan(plan)
            }
        }
        return Result(plan.protocols.keys.toList(), plan.images.toList())
    }

    private const val NEW_MODEL_REFRESH_MS = 10L * 60 * 1000
    private val newModelRefresh = Mutex()
    private var newModelRefreshAt = 0L

    /**
     * Models just added to [configId] that the local catalogs do not know yet: downloads the newest
     * catalogs (at most once every ten minutes) and fills those models in from them. Models the
     * local catalogs know were filled in when the list was saved, so they need no download. A call
     * made while a download runs waits for it and fills its own models from the result.
     */
    suspend fun adaptNewModels(context: Context, configId: String, models: Collection<String>): Result =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val none = Result(emptyList(), emptyList())
            val config = ModelConfigManager(app).getModelConfig(configId) ?: return@withContext none
            val listed = getModelList(config.modelName).filter { it in models }
            if (listed.isEmpty()) return@withContext none
            // Matched and filled under the catalogs' lock: a refresh under way anywhere (one catalog
            // new, the other not yet) is waited for, so the batch never reads a half-updated pair.
            newModelRefresh.withLock {
                val unknown = refreshing.withLock {
                    listed - matched(config.apiProviderTypeId, config.apiEndpoint, listed, loadCatalogs(app))
                }
                val now = System.currentTimeMillis()
                if (unknown.isNotEmpty() && now - newModelRefreshAt !in 0 until NEW_MODEL_REFRESH_MS) {
                    newModelRefreshAt = now
                    refreshCatalogs(app)
                }
            }
            // The whole batch: one that became known after the save is filled in from the newest too.
            refreshing.withLock { fill(app, configId, listed) }
        }

    /**
     * Downloads the newest catalogs, then replaces what they know about every model of [configId]
     * (see [plan] with force). A catalog that fails to download keeps its local copy. A model whose
     * fields differ from [before] by then was changed meanwhile and is left as it is, and so is one
     * [editing] names (edits about to be saved).
     */
    suspend fun readapt(
        context: Context,
        configId: String,
        before: ModelAutoFields,
        editing: () -> Set<String> = { emptySet() },
    ): Readapt {
        val app = context.applicationContext
        val refresh = refreshCatalogs(app)
        val catalogs = loadCatalogs(app)
        var plan = Plan(emptyMap(), emptyMap(), emptyMap(), emptySet(), emptySet())
        var kept = emptySet<String>()
        val config = ModelConfigManager(app).editModelConfig(configId) { current ->
            val now = ModelAutoFields.of(current)
            val busy = editing()
            val (same, changed) = getModelList(current.modelName).partition { it !in busy && now.sameFor(it, before) }
            kept = changed.toSet()
            plan = plan(current, same, catalogs, force = true)
            current.withPlan(plan)
        }
        return Readapt(config, plan, refresh, kept)
    }

    /**
     * Downloads both catalogs now; each one's success is recorded on its own. Holds the same lock
     * as [refreshIfStale], so a check never sees a new catalog file before its contents are in use.
     * A catalog downloaded by someone else while this waited for the lock is not fetched again.
     */
    suspend fun refreshCatalogs(context: Context): Refresh {
        val app = context.applicationContext
        val asked = mapOf(CAPABILITIES to (refreshed[CAPABILITIES] ?: 0L), PROTOCOLS to (refreshed[PROTOCOLS] ?: 0L))
        return refreshing.withLock {
            fun doneSince(what: String) = (refreshed[what] ?: 0L) > asked.getValue(what)
            Refresh(
                capabilities = doneSince(CAPABILITIES) || refreshOne(app, CAPABILITIES),
                protocols = doneSince(PROTOCOLS) || refreshOne(app, PROTOCOLS),
            )
        }
    }

    private suspend fun refreshOne(app: Context, what: String): Boolean {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong("tried_at_$what", System.currentTimeMillis()).apply()
        return try {
            if (what == CAPABILITIES) OfficialModelCapabilitiesRepository(app).refreshCatalog()
            else ModelProtocolCatalogRepository(app).refreshCatalog()
            prefs.edit().putLong("refreshed_at_$what", System.currentTimeMillis()).apply()
            refreshed.merge(what, 1L, Long::plus)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not refresh the $what catalog: ${e.message}")
            false
        }
    }

    /**
     * Fetches each catalog that is a week old (one that failed waits a day), then fills in every
     * saved configuration whenever the catalogs in use changed since the last time that finished.
     * Filling never needs the network, so offline it fills in from the local catalogs.
     */
    suspend fun refreshIfStale(context: Context) {
        applying.withLock {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // The version and the catalogs are taken together under the lock; filling in needs only
            // them, so a re-match does not wait for it. Should the catalogs change meanwhile, the
            // version recorded is the older one and the next check fills in again.
            val (version, catalogs) = refreshing.withLock {
                val now = System.currentTimeMillis()
                fun fresh(key: String, window: Long) = (now - prefs.getLong(key, 0L)) in 0 until window
                for (what in listOf(CAPABILITIES, PROTOCOLS)) {
                    if (!fresh("refreshed_at_$what", REFRESH_EVERY_MS) && !fresh("tried_at_$what", RETRY_AFTER_MS)) {
                        refreshOne(app, what)
                    }
                }
                // Taken before the catalogs are read: a refresh landing in between only means the
                // next check fills in once more, never that a newer catalog is marked as applied.
                val version = catalogsVersion(app)
                if (prefs.getString(APPLIED, null) == version) return
                val catalogs = loadCatalogs(app)
                if (catalogs.protocols == null || catalogs.capabilities == null) return
                version to catalogs
            }
            // Each configuration is written only while these catalogs are still the ones in use, and
            // they count as applied only if they still are at the end; otherwise the next check fills
            // in from the newer ones.
            val current = { catalogsVersion(app) == version }
            if (applyAll(app, catalogs, current) && current()) prefs.edit().putString(APPLIED, version).apply()
        }
    }

    /** Which catalogs are in use, from the app version and the refreshed copies' file times. */
    private fun catalogsVersion(app: Context): String = listOf(
        BuildConfig.VERSION_CODE,
        OfficialModelCapabilitiesRepository(app).localVersion(),
        ModelProtocolCatalogRepository(app).localVersion(),
    ).joinToString(":")

    /** Makes the next check fill in every configuration again, as after restoring a backup. */
    fun invalidate(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(APPLIED).apply()
    }

    /**
     * The local catalogs for filling in restored configurations as they are written; null (and the
     * next check fills in everything) when one could not be read.
     */
    suspend fun catalogsForImport(context: Context): Catalogs? {
        val catalogs = loadCatalogs(context.applicationContext)
        if (catalogs.protocols != null && catalogs.capabilities != null) return catalogs
        invalidate(context)
        return null
    }

    /** Fills in every saved configuration while [current]; false when one of them could not be. */
    private suspend fun applyAll(context: Context, catalogs: Catalogs, current: () -> Boolean): Boolean {
        val configs = ModelConfigManager(context)
        configs.initializeIfNeeded()
        var done = true
        configs.getAllConfigSummaries().forEach { summary ->
            try {
                // Checked and written while no refresh can publish a newer catalog, so an older
                // one never fills a gap the newer one would have filled differently.
                val written = refreshing.withLock {
                    current() && run { fill(context, summary.id, catalogs = catalogs, current = current); true }
                }
                if (!written) return false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                done = false
                AppLogger.w(TAG, "Could not auto-configure ${summary.id}: ${e.message}")
            }
        }
        return done
    }

    /** Runs [refreshIfStale] in the background about once a day, when there is a network. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.DAYS)
            // Each start checks anyway, so the first background run can wait a day.
            .setInitialDelay(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = try {
            refreshIfStale(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "Scheduled model catalog refresh failed: ${e.message}")
            Result.success()
        }
    }
}
