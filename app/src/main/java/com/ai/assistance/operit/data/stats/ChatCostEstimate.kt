package com.ai.assistance.operit.data.stats

import android.content.Context
import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.pricing.ModelPricingCatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ChatCostEstimate(val cny: Double, val incomplete: Boolean)

/** A current-price estimate from saved reply usage, not a historical invoice. */
internal suspend fun estimateChatCost(context: Context, messages: List<ChatMessage>): ChatCostEstimate =
    withContext(Dispatchers.IO) {
        ModelPricingCatalogRepository.getInstance(context)
        val overrides = AppDatabase.getDatabase(context).tokenStatsDao().getAllPriceOverrides()
        val preferences = ApiPreferences.getInstance(context)
        val legacy = preferences.allLegacyPriceSettings()
        val rate = preferences.usdToCnyRateWithEstimate().first
        var total = 0.0
        var incomplete = false
        for (message in messages) {
            if (message.sender != "ai") continue
            if (message.inputTokens <= 0 && message.outputTokens <= 0) {
                if (message.content.isNotBlank()) incomplete = true
                continue
            }
            val providerModel = "${message.provider}:${message.modelName}"
            val defaults = DefaultModelPricingCollect.getDefaultPricing(providerModel)
            val pricing = TokenPriceResolver.resolve(
                provider = message.provider,
                model = message.modelName,
                configId = null,
                overrides = overrides,
                legacyOverride = legacy[providerModel],
                defaults = defaults,
            )
            // Saved messages aggregate a whole turn, so request boundaries and cache writes
            // cannot be reconstructed. Use base token rates and disclose incomplete estimates.
            if (pricing.billingMode == com.ai.assistance.operit.data.model.BillingMode.COUNT) {
                incomplete = true
                continue
            }
            if (defaults.contextTiers.isNotEmpty() ||
                message.modelName.contains("claude", ignoreCase = true) ||
                message.provider.contains("claude", ignoreCase = true) ||
                message.provider.contains("anthropic", ignoreCase = true)
            ) incomplete = true
            val cost = TokenCostCalculator.computeCost(
                TokenUsageInput(
                    uncachedInputTokens = (message.inputTokens - message.cachedInputTokens).coerceAtLeast(0).toLong(),
                    cachedInputTokens = message.cachedInputTokens.coerceAtLeast(0).toLong(),
                    cacheWriteTokens = 0,
                    outputTokens = message.outputTokens.coerceAtLeast(0).toLong(),
                ),
                pricing,
            ).amount
            val converted = cost?.let {
                TokenCostCurrency.convertTo(it, pricing.currency, PricingCurrency.CNY, rate)
            }
            if (converted == null || !(total + converted).isFinite()) incomplete = true
            else total += converted
        }
        ChatCostEstimate(total, incomplete)
    }
