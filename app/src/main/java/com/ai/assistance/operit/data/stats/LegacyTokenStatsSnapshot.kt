package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.BillingMode

/** 旧 DataStore 中某个 provider:model 的累计统计与用户价格设置。 */
data class LegacyProviderModelStats(
    val providerModel: String,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    val requestCount: Long,
    val priceSettings: LegacyPriceSettings,
) {
    val hasAnyData: Boolean
        get() = inputTokens > 0L || cachedInputTokens > 0L || outputTokens > 0L || requestCount > 0L
}

/**
 * 旧 DataStore（api_settings）累计统计的一次快照解析。
 *
 * 键约定与 [com.ai.assistance.operit.data.preferences.ApiPreferences] 保持一致：
 * `token_input_<encoded>` / `token_cached_input_<encoded>` / `token_output_<encoded>`
 * （Long）、`request_count_<encoded>`（Int）、`model_input_price_<encoded>` 等（Float）、
 * `billing_mode_<encoded>`（String）、`price_per_request_<encoded>`（Float），
 * 其中 `<encoded>` 为 “provider:model” 的 “:” 被替换为 “_” 后的形式。
 *
 * 旧系统语义：键缺失 = 该计数为 0（累计值从 0 开始）；价格键缺失 = 未设置。
 * 解析结果只用于迁移估算，不保留正文或凭据。
 */
data class LegacyTokenStatsSnapshot(
    val providerModels: Map<String, LegacyProviderModelStats>,
) {
    companion object {

        private val providerNameCandidates =
            ApiProviderType.entries.map { it.name }.sortedByDescending { it.length }

        fun parse(rawPreferences: Map<String, Any?>): LegacyTokenStatsSnapshot {
            val builders = linkedMapOf<String, StatsBuilder>()

            rawPreferences.forEach { (key, value) ->
                val keyName = key
                if (keyName.startsWith(TOKEN_INPUT_PREFIX)) {
                    val providerModel =
                        decodeProviderModelFromKeySuffix(keyName.removePrefix(TOKEN_INPUT_PREFIX))
                    if (providerModel.isNotBlank()) {
                        builders.getOrPut(providerModel) { StatsBuilder(providerModel) }
                            .inputTokens = readTokenCountValue(value)
                    }
                }
            }

            rawPreferences.forEach { (key, value) ->
                val keyName = key
                if (keyName.startsWith(TOKEN_CACHED_PREFIX)) {
                    val providerModel =
                        decodeProviderModelFromKeySuffix(keyName.removePrefix(TOKEN_CACHED_PREFIX))
                    if (providerModel.isNotBlank()) {
                        builders.getOrPut(providerModel) { StatsBuilder(providerModel) }
                            .cachedInputTokens = readTokenCountValue(value)
                    }
                }
            }

            rawPreferences.forEach { (key, value) ->
                val keyName = key
                if (keyName.startsWith(TOKEN_OUTPUT_PREFIX)) {
                    val providerModel =
                        decodeProviderModelFromKeySuffix(keyName.removePrefix(TOKEN_OUTPUT_PREFIX))
                    if (providerModel.isNotBlank()) {
                        builders.getOrPut(providerModel) { StatsBuilder(providerModel) }
                            .outputTokens = readTokenCountValue(value)
                    }
                }
            }

            rawPreferences.forEach { (key, value) ->
                val keyName = key
                if (keyName.startsWith(REQUEST_COUNT_PREFIX)) {
                    val providerModel =
                        decodeProviderModelFromKeySuffix(keyName.removePrefix(REQUEST_COUNT_PREFIX))
                    if (providerModel.isNotBlank()) {
                        builders.getOrPut(providerModel) { StatsBuilder(providerModel) }
                            .requestCount = (value as? Int)?.toLong() ?: 0L
                    }
                }
            }

            rawPreferences.forEach { (key, value) ->
                val keyName = key
                if (keyName.startsWith(PRICE_INPUT_PREFIX)) {
                    val providerModel =
                        decodeProviderModelFromKeySuffix(keyName.removePrefix(PRICE_INPUT_PREFIX))
                    if (providerModel.isNotBlank()) {
                        val price = (value as? Float)?.toDouble()
                        if (price != null) {
                            builders.getOrPut(providerModel) { StatsBuilder(providerModel) }
                                .priceSettings =
                                builders.getValue(providerModel).priceSettings.copy(
                                    inputPricePerMillion = price.takeIf { it > 0.0 }
                                )
                        }
                    }
                }
            }

            rawPreferences.forEach { (key, value) ->
                val keyName = key
                if (keyName.startsWith(PRICE_CACHED_PREFIX)) {
                    val providerModel =
                        decodeProviderModelFromKeySuffix(keyName.removePrefix(PRICE_CACHED_PREFIX))
                    if (providerModel.isNotBlank()) {
                        val price = (value as? Float)?.toDouble()
                        if (price != null) {
                            builders.getOrPut(providerModel) { StatsBuilder(providerModel) }
                                .priceSettings =
                                builders.getValue(providerModel).priceSettings.copy(
                                    cachedInputPricePerMillion = price.takeIf { it > 0.0 }
                                )
                        }
                    }
                }
            }

            rawPreferences.forEach { (key, value) ->
                val keyName = key
                if (keyName.startsWith(PRICE_OUTPUT_PREFIX)) {
                    val providerModel =
                        decodeProviderModelFromKeySuffix(keyName.removePrefix(PRICE_OUTPUT_PREFIX))
                    if (providerModel.isNotBlank()) {
                        val price = (value as? Float)?.toDouble()
                        if (price != null) {
                            builders.getOrPut(providerModel) { StatsBuilder(providerModel) }
                                .priceSettings =
                                builders.getValue(providerModel).priceSettings.copy(
                                    outputPricePerMillion = price.takeIf { it > 0.0 }
                                )
                        }
                    }
                }
            }

            rawPreferences.forEach { (key, value) ->
                val keyName = key
                if (keyName.startsWith(BILLING_MODE_PREFIX)) {
                    val providerModel =
                        decodeProviderModelFromKeySuffix(keyName.removePrefix(BILLING_MODE_PREFIX))
                    if (providerModel.isNotBlank()) {
                        val mode = BillingMode.fromString(value as? String)
                        builders.getOrPut(providerModel) { StatsBuilder(providerModel) }
                            .priceSettings =
                            builders.getValue(providerModel).priceSettings.copy(
                                billingMode = mode
                            )
                    }
                }
            }

            rawPreferences.forEach { (key, value) ->
                val keyName = key
                if (keyName.startsWith(PRICE_PER_REQUEST_PREFIX)) {
                    val providerModel =
                        decodeProviderModelFromKeySuffix(keyName.removePrefix(PRICE_PER_REQUEST_PREFIX))
                    if (providerModel.isNotBlank()) {
                        val price = (value as? Float)?.toDouble()
                        if (price != null) {
                            builders.getOrPut(providerModel) { StatsBuilder(providerModel) }
                                .priceSettings =
                                builders.getValue(providerModel).priceSettings.copy(
                                    pricePerRequest = price.takeIf { it > 0.0 }
                                )
                        }
                    }
                }
            }

            return LegacyTokenStatsSnapshot(
                providerModels =
                    builders.values
                        .map { it.build() }
                        .filter { it.hasAnyData }
                        .associateBy { it.providerModel }
            )
        }

        private class StatsBuilder(val providerModel: String) {
            var inputTokens: Long = 0L
            var cachedInputTokens: Long = 0L
            var outputTokens: Long = 0L
            var requestCount: Long = 0L
            var priceSettings: LegacyPriceSettings = LegacyPriceSettings()

            fun build(): LegacyProviderModelStats =
                LegacyProviderModelStats(
                    providerModel = providerModel,
                    inputTokens = inputTokens,
                    cachedInputTokens = cachedInputTokens,
                    outputTokens = outputTokens,
                    requestCount = requestCount,
                    priceSettings = priceSettings,
                )
        }

        private fun readTokenCountValue(value: Any?): Long =
            when (value) {
                is Long -> value
                is Int -> if (value < 0) value.toLong() and 0xFFFF_FFFFL else value.toLong()
                else -> 0L
            }

        /** 与 ApiPreferences 一致的 “provider:model” 后缀解码。 */
        private fun decodeProviderModelFromKeySuffix(encoded: String): String {
            val matchedProvider =
                providerNameCandidates.firstOrNull {
                    encoded == it || encoded.startsWith("${it}_")
                }
            return if (matchedProvider != null) {
                if (encoded.length == matchedProvider.length) {
                    matchedProvider
                } else {
                    "$matchedProvider:${encoded.substring(matchedProvider.length + 1)}"
                }
            } else {
                encoded.replaceFirst("_", ":")
            }
        }

        private const val TOKEN_INPUT_PREFIX = "token_input_"
        private const val TOKEN_CACHED_PREFIX = "token_cached_input_"
        private const val TOKEN_OUTPUT_PREFIX = "token_output_"
        private const val REQUEST_COUNT_PREFIX = "request_count_"
        private const val PRICE_INPUT_PREFIX = "model_input_price_"
        private const val PRICE_CACHED_PREFIX = "model_cached_input_price_"
        private const val PRICE_OUTPUT_PREFIX = "model_output_price_"
        private const val BILLING_MODE_PREFIX = "billing_mode_"
        private const val PRICE_PER_REQUEST_PREFIX = "price_per_request_"
    }
}
