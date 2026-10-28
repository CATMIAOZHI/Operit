package com.ai.assistance.operit.data.model

import android.content.Context
import com.ai.assistance.operit.api.chat.llmprovider.OpenCodeZenFree
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException

/**
 * Fills in what the local model catalogs know about a configuration's models: the request protocol
 * (and with it how thinking strength is sent) and whether a model reads pictures, audio or video.
 *
 * It only adds: a model that already has a protocol keeps it, and a capability the user switched on
 * stays on. This is the same matching as the "auto-configure" buttons in the model settings, done
 * without them, for configurations Orbit creates and once on the first start.
 */
object ModelAutoConfig {
    private const val TAG = "ModelAutoConfig"
    private const val PREFS = "model_auto_config"
    private const val FIRST_REFRESH_DONE = "first_refresh_done_v1"

    data class Result(
        /** Models given a protocol from the catalog. */
        val protocols: List<String>,
        /** Models the catalog says read pictures. */
        val images: List<String>,
    )

    /**
     * Applies the catalogs to [configId], for [models] only when given (else every model in it).
     * Never touches the network; the catalogs are the bundled or last refreshed copies.
     */
    suspend fun fill(context: Context, configId: String, models: Collection<String>? = null): Result {
        val app = context.applicationContext
        val configs = ModelConfigManager(app)
        val config = configs.getModelConfig(configId) ?: return Result(emptyList(), emptyList())
        val provider = ApiProviderType.fromProviderTypeId(config.apiProviderTypeId) ?: config.apiProviderType
        val names = getModelList(config.modelName).filter { models == null || it in models }
        if (names.isEmpty()) return Result(emptyList(), emptyList())

        // OpenCode Zen Free has no protocol choice in the settings, yet its models' entries (how
        // thinking strength is sent) come from the catalog under its own chat address, as the model
        // settings page reads them; the catalog's word replaces what an older catalog saved.
        val zenFree = provider == ApiProviderType.OPENCODE_ZEN_FREE
        val protocols = if (zenFree || supportsModelProtocolOverrides(config.apiProviderTypeId)) {
            runCatching {
                ModelProtocolCatalogRepository(app).loadCatalog()
                    .matchAll(if (zenFree) OpenCodeZenFree.CHAT_ENDPOINT else config.apiEndpoint, names)
            }.onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyMap())
        } else emptyMap()
        val capabilities = runCatching {
            val catalog = OfficialModelCapabilitiesRepository(app).loadCatalog()
            if (provider == ApiProviderType.OPENCODE_ZEN_FREE) OpenCodeZenFree.matchMultimodalCapabilities(catalog, names)
            else catalog.matchAll(names)
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyMap())
        if (protocols.isEmpty() && capabilities.isEmpty()) return Result(emptyList(), emptyList())

        var added = emptyList<String>()
        var images = emptyList<String>()
        configs.editModelConfig(configId) { current ->
            val listed = getModelList(current.modelName).toSet()
            val newProtocols = protocols.filter { (name, settings) ->
                name in listed && (if (zenFree) current.modelProtocolSettings[name] != settings
                    else name !in current.modelProtocolSettings)
            }
            val newCaps = capabilities.filterKeys { it in listed }
            added = newProtocols.keys.toList()
            images = newCaps.filterValues { it.image }.keys.toList()
            if (newProtocols.isEmpty() && newCaps.isEmpty()) return@editModelConfig current
            // Every listed model gets its own entry so the ones not matched keep what they had.
            val merged = getModelList(current.modelName).associateWith { name ->
                val had = current.multimodalCapabilitiesForModel(name)
                val found = newCaps[name] ?: return@associateWith had
                ModelMultimodalCapabilities(
                    image = had.image || found.image,
                    audio = had.audio || found.audio,
                    video = had.video || found.video,
                )
            }
            current.copy(
                modelProtocolSettings = current.modelProtocolSettings + newProtocols,
                modelMultimodalCapabilities = merged,
            )
        }
        return Result(added, images)
    }

    /**
     * On the first start, fetches the newest catalogs and applies them to every saved configuration.
     * A failed download, or a configuration that could not be filled in, is tried again at the next
     * start; applying never needs the network.
     */
    suspend fun firstStart(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(FIRST_REFRESH_DONE, false)) return
        var done = true
        listOf<suspend () -> Unit>(
            { ModelProtocolCatalogRepository(app).refreshCatalog() },
            { OfficialModelCapabilitiesRepository(app).refreshCatalog() },
        ).forEach { refresh ->
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                done = false
                AppLogger.w(TAG, "Could not refresh a model catalog: ${e.message}")
            }
        }
        val configs = ModelConfigManager(app)
        configs.initializeIfNeeded()
        configs.getAllConfigSummaries().forEach { summary ->
            try {
                fill(app, summary.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                done = false
                AppLogger.w(TAG, "Could not auto-configure ${summary.id}: ${e.message}")
            }
        }
        if (done) prefs.edit().putBoolean(FIRST_REFRESH_DONE, true).apply()
    }
}
