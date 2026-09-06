package com.ai.assistance.operit.data.collects

import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.pricing.PricingCatalogDocument

enum class PricingCurrency(
    val code: String,
    val symbol: String
) {
    CNY("CNY", "¥"),
    USD("USD", "$")
}

data class ModelPricingDefaults(
    val billingMode: BillingMode,
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double,
    val cachedInputPricePerMillion: Double,
    val pricePerRequest: Double,
    val currency: PricingCurrency,
    /** True when the source explicitly knows all prices needed by the billing mode. */
    val known: Boolean = false,
    /** Nullable because most legacy/default rows do not publish cache-write pricing. */
    val cacheWritePricePerMillion: Double? = null,
    /** Per-field presence preserves catalog null versus an explicit zero without breaking legacy callers. */
    val hasInputPrice: Boolean = known || inputPricePerMillion > 0.0,
    val hasCachedInputPrice: Boolean = known || cachedInputPricePerMillion > 0.0,
    val hasOutputPrice: Boolean = known || outputPricePerMillion > 0.0,
    val hasPricePerRequest: Boolean = known || pricePerRequest > 0.0,
    val contextTiers: List<com.ai.assistance.operit.data.pricing.ContextPriceTier> = emptyList(),
) {
    fun forContext(inputTokens: Long?): ModelPricingDefaults {
        if (contextTiers.isEmpty() || billingMode != BillingMode.TOKEN) return this
        if (inputTokens == null) return copy(
            known = false, hasInputPrice = false, hasCachedInputPrice = false,
            hasOutputPrice = false, cacheWritePricePerMillion = null,
        )
        val tier = contextTiers.filter { inputTokens > it.minInputTokens }
            .maxByOrNull { it.minInputTokens } ?: return this
        return copy(
            inputPricePerMillion = tier.input,
            outputPricePerMillion = tier.output,
            cachedInputPricePerMillion = tier.cacheRead ?: tier.input,
            cacheWritePricePerMillion = tier.cacheWrite,
            hasInputPrice = true, hasOutputPrice = true, hasCachedInputPrice = true, known = true,
        )
    }
}

object DefaultModelPricingCollect {
    @Volatile
    private var catalog = com.ai.assistance.operit.data.pricing.OfficialPricingIndex(emptyList())

    /** Replaces the official directory. User overrides are resolved separately. */
    fun installCatalog(document: PricingCatalogDocument) {
        catalog = com.ai.assistance.operit.data.pricing.OfficialPricingIndex(document.entries)
    }

    fun getDefaultPricing(providerModel: String): ModelPricingDefaults {
        val model = providerModel.substringAfter(':', providerModel)
        return catalog.lookup(model) ?: ModelPricingDefaults(
            billingMode = BillingMode.TOKEN,
            inputPricePerMillion = 0.0,
            outputPricePerMillion = 0.0,
            cachedInputPricePerMillion = 0.0,
            pricePerRequest = 0.0,
            currency = PricingCurrency.USD,
        )
    }

    fun getCurrency(providerModel: String): PricingCurrency = getDefaultPricing(providerModel).currency
}
