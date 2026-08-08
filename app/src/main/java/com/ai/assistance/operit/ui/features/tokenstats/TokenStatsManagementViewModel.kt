package com.ai.assistance.operit.ui.features.tokenstats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.model.PriceOverrideScope
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.stats.LegacyPriceSettings
import com.ai.assistance.operit.data.stats.TokenStatsGroupModelInfo
import com.ai.assistance.operit.data.stats.TokenStatsPriceOverrideDraft
import com.ai.assistance.operit.data.stats.TokenStatsSettingsManager
import com.ai.assistance.operit.plugins.toolpkg.ToolPkgAiProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TokenStatsConfigOption(
    val id: String,
    val name: String,
    val provider: String,
    val models: List<String>,
    val endpoint: String = "",
    val available: Boolean = true,
)

data class TokenStatsPricingModelOption(
    val provider: String,
    val model: String,
    val configs: List<TokenStatsConfigOption>,
    val legacyProviderModel: String? = null,
    val legacyPricing: LegacyPriceSettings? = null,
)

data class TokenStatsManagementState(
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val groups: List<TokenStatsGroupModelInfo> = emptyList(),
    val overrides: List<TokenStatPriceOverrideEntity> = emptyList(),
    val configs: List<TokenStatsConfigOption> = emptyList(),
    val pricingModels: List<TokenStatsPricingModelOption> = emptyList(),
)

class TokenStatsManagementViewModel(
    context: Context,
    dao: TokenStatsDao? = null,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val manager = TokenStatsSettingsManager(
        dao ?: AppDatabase.getDatabase(appContext).tokenStatsDao()
    )
    private val configManager = ModelConfigManager(appContext)
    private val apiPreferences = ApiPreferences.getInstance(appContext)
    private val _state = MutableStateFlow(TokenStatsManagementState())
    val state: StateFlow<TokenStatsManagementState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, errorMessage = null) }
            try {
                val groups = manager.groupModels()
                val overrides = manager.allPriceOverrides()
                val legacyPrices = apiPreferences.allLegacyPriceSettings().mapNotNull { (key, value) ->
                    value?.let { key to it }
                }.toMap()
                val configs = configManager.getAllConfigSummaries().map(ModelConfigSummary::toTokenStatsOption)
                _state.value = TokenStatsManagementState(
                    loading = false,
                    groups = groups,
                    overrides = overrides,
                    configs = configs,
                    pricingModels = buildPricingModels(groups, configs, overrides, legacyPrices),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        errorMessage = appContext.getString(R.string.token_stats_management_load_failed),
                    )
                }
            }
        }
    }

    fun renameGroup(groupId: String, name: String) = mutate {
        manager.renameDisplayGroup(groupId, name)
    }

    fun createGroup(name: String, identityIds: List<String>) = mutate {
        manager.createGroupAndMove(name, identityIds)
    }

    fun moveToGroup(identityIds: List<String>, targetGroupId: String) = mutate {
        manager.moveIdentitiesToGroup(identityIds, targetGroupId)
    }

    fun restoreDefaultGroup(groupId: String) = mutate {
        manager.restoreDefaultGroups(groupId)
    }

    fun savePriceOverride(
        existing: TokenStatPriceOverrideEntity?,
        draft: TokenStatsPriceOverrideDraft,
    ) = mutate {
        if (existing == null) manager.upsertPriceOverride(draft)
        else manager.updatePriceOverride(existing, draft)
    }

    fun resetPrice(override: TokenStatPriceOverrideEntity) = mutate {
        val scope = PriceOverrideScope.fromNameOrNull(override.scope) ?: return@mutate
        manager.deletePriceOverride(scope, override.provider, override.model, override.configId)
    }

    fun restoreBuiltInPrice(
        providerOverride: TokenStatPriceOverrideEntity?,
        legacyProviderModel: String?,
    ) = mutate {
        if (providerOverride != null) {
            manager.deletePriceOverride(
                PriceOverrideScope.PROVIDER_MODEL,
                providerOverride.provider,
                providerOverride.model,
                providerOverride.configId,
            )
        }
        if (legacyProviderModel != null) {
            apiPreferences.clearLegacyPriceSettings(legacyProviderModel)
        }
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(errorMessage = appContext.getString(R.string.token_stats_management_save_failed))
                }
            }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TokenStatsManagementViewModel(appContext) as T
    }
}

private fun ModelConfigSummary.toTokenStatsOption() = TokenStatsConfigOption(
    id = id,
    name = name,
    // ToolPkg 事件的 provider 记录为 displayName；未产生事件时也按同一名称解析，
    // 否则价格覆盖保存后 TokenPriceResolver 按 displayName 查不到。
    provider = ToolPkgAiProviderRegistry.get(apiProviderTypeId)?.displayName ?: apiProviderTypeId,
    models = getModelList(modelName),
    endpoint = apiEndpoint,
)

internal fun buildPricingModels(
    groups: List<TokenStatsGroupModelInfo>,
    configs: List<TokenStatsConfigOption>,
    overrides: List<TokenStatPriceOverrideEntity>,
    legacyPrices: Map<String, LegacyPriceSettings> = emptyMap(),
): List<TokenStatsPricingModelOption> {
    val observedMembers = groups.flatMap { it.members }
    fun observedProvider(configId: String, model: String): String? =
        observedMembers.firstOrNull { member ->
            member.configId == configId && member.model.equals(model, ignoreCase = true)
        }?.provider

    val keys = linkedMapOf<String, Pair<String, String>>()
    fun add(provider: String, model: String) {
        val key = "${provider.trim().lowercase()}\u0000${model.trim().lowercase()}"
        keys.putIfAbsent(key, provider to model)
    }
    observedMembers.forEach { add(it.provider, it.model) }
    configs.forEach { config ->
        config.models.forEach { model ->
            add(observedProvider(config.id, model) ?: config.provider, model)
        }
    }
    overrides.forEach { add(it.provider, it.model) }
    legacyPrices.keys.forEach { providerModel ->
        val separator = providerModel.indexOf(':')
        if (separator > 0) add(providerModel.substring(0, separator), providerModel.substring(separator + 1))
    }
    return keys.values.map { (provider, model) ->
        val matchingConfigs = configs.filter { config ->
            config.models.any { it.equals(model, ignoreCase = true) } &&
                (observedProvider(config.id, model) ?: config.provider)
                    .equals(provider, ignoreCase = true)
        }
        val missingConfigIds = overrides.asSequence().filter {
            it.scope == PriceOverrideScope.CONFIG.name &&
                it.provider.equals(provider, true) && it.model.equals(model, true)
        }.map { it.configId }.filter { id -> matchingConfigs.none { it.id == id } }.distinct()
        TokenStatsPricingModelOption(
            provider = provider,
            model = model,
            configs = matchingConfigs + missingConfigIds.map { id ->
                TokenStatsConfigOption(
                    id = id,
                    name = id,
                    provider = provider,
                    models = listOf(model),
                    available = false,
                )
            },
            legacyProviderModel = legacyPrices.keys.firstOrNull { key ->
                val separator = key.indexOf(':')
                separator > 0 && key.substring(0, separator).equals(provider, true) &&
                    key.substring(separator + 1).equals(model, true)
            },
            legacyPricing = legacyPrices.entries.firstOrNull { (key, _) ->
                val separator = key.indexOf(':')
                separator > 0 && key.substring(0, separator).equals(provider, true) &&
                    key.substring(separator + 1).equals(model, true)
            }?.value,
        )
    }.sortedWith(compareBy({ it.model.lowercase() }, { it.provider.lowercase() }))
}
