package com.ai.assistance.operit.data.collects

import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.pricing.PricingCatalogDocument
import com.ai.assistance.operit.data.pricing.PricingCatalogJson

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
)

object DefaultModelPricingCollect {
    private data class CatalogIndex(
        val exact: Map<String, ModelPricingDefaults> = emptyMap(),
        val aliases: Map<String, ModelPricingDefaults> = emptyMap(),
    )

    @Volatile
    private var activeCatalog = CatalogIndex()

    /** Installs an already validated immutable snapshot; never performs I/O. */
    fun installCatalog(document: PricingCatalogDocument) {
        val exact = HashMap<String, ModelPricingDefaults>(document.entries.size)
        val aliases = HashMap<String, ModelPricingDefaults>()
        document.entries.forEach { entry ->
            val defaults = PricingCatalogJson.entryToDefaults(entry)
            val provider = PricingCatalogJson.normalize(entry.provider)
            exact["$provider:${PricingCatalogJson.normalize(entry.model)}"] = defaults
            entry.aliases.forEach { alias ->
                aliases["$provider:${PricingCatalogJson.normalize(alias)}"] = defaults
            }
        }
        activeCatalog = CatalogIndex(exact = exact.toMap(), aliases = aliases.toMap())
    }
    private val domesticProviders = setOf(
        "DEEPSEEK",
        "BAIDU",
        "ALIYUN",
        "XUNFEI",
        "ZHIPU",
        "BAICHUAN",
        "MOONSHOT",
        "SILICONFLOW",
        "FOUR_ROUTER",
        "INFINIAI",
        "ALIPAY_BAILING",
        "DOUBAO",
        "PPINFRA",
        "OPENAI_LOCAL",
        "MIMO"
    )

    private fun defaultPricePerRequest(currency: PricingCurrency): Double {
        return when (currency) {
            PricingCurrency.CNY -> 0.01
            PricingCurrency.USD -> 0.001
        }
    }

    private fun zeroPricing(currency: PricingCurrency): ModelPricingDefaults {
        return ModelPricingDefaults(
            billingMode = BillingMode.TOKEN,
            inputPricePerMillion = 0.0,
            outputPricePerMillion = 0.0,
            cachedInputPricePerMillion = 0.0,
            pricePerRequest = defaultPricePerRequest(currency),
            currency = currency
        )
    }

    private fun tokenPricing(
        inputPricePerMillion: Double,
        outputPricePerMillion: Double,
        cachedInputPricePerMillion: Double = inputPricePerMillion,
        currency: PricingCurrency,
        pricePerRequest: Double = defaultPricePerRequest(currency)
    ): ModelPricingDefaults {
        return ModelPricingDefaults(
            billingMode = BillingMode.TOKEN,
            inputPricePerMillion = inputPricePerMillion,
            outputPricePerMillion = outputPricePerMillion,
            cachedInputPricePerMillion = cachedInputPricePerMillion,
            pricePerRequest = pricePerRequest,
            currency = currency,
            known = true,
        )
    }

    private fun countPricing(
        pricePerRequest: Double,
        currency: PricingCurrency
    ): ModelPricingDefaults {
        return ModelPricingDefaults(
            billingMode = BillingMode.COUNT,
            inputPricePerMillion = 0.0,
            outputPricePerMillion = 0.0,
            cachedInputPricePerMillion = 0.0,
            pricePerRequest = pricePerRequest,
            currency = currency,
            known = true,
        )
    }

    private data class ScrapedPricingRow(
        val provider: String,
        val model: String,
        val defaults: ModelPricingDefaults
    )

    private val scrapedPricingRows: List<ScrapedPricingRow> by lazy {
        buildList {
            ScrapedModelPricingRowsCollect.rows
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { row ->
                    val parts = row.split('|')
                    if (parts.size < 7) return@forEach

                    val provider = parts[0].trim().lowercase()
                    val model = parts[1].trim()
                    val billingMode = BillingMode.fromString(parts[2].trim())
                    val inputPrice = parts[3].trim().toDoubleOrNull() ?: 0.0
                    val outputPrice = parts[4].trim().toDoubleOrNull() ?: 0.0
                    val tokenCachedOrCountPrice = parts[5].trim().toDoubleOrNull() ?: 0.0
                    val currency = if (parts[6].trim().equals("CNY", ignoreCase = true)) {
                        PricingCurrency.CNY
                    } else {
                        PricingCurrency.USD
                    }

                    val defaults = if (billingMode == BillingMode.COUNT) {
                        countPricing(
                            pricePerRequest = tokenCachedOrCountPrice,
                            currency = currency
                        )
                    } else {
                        tokenPricing(
                            inputPricePerMillion = inputPrice,
                            outputPricePerMillion = outputPrice,
                            cachedInputPricePerMillion =
                                tokenCachedOrCountPrice.takeIf { it > 0.0 } ?: inputPrice,
                            currency = currency
                        )
                    }

                    add(
                        ScrapedPricingRow(
                            provider = provider,
                            model = model,
                            defaults = defaults
                        )
                    )
                }
        }
    }

    private val scrapedModelDefaults: Map<String, ModelPricingDefaults> by lazy {
        scrapedPricingRows.associate {
            "${PricingCatalogJson.normalize(it.provider)}:${PricingCatalogJson.normalize(it.model)}" to it.defaults
        }
    }

    private val providerFallbacks = mapOf(
        "OPENAI" to zeroPricing(PricingCurrency.USD),
        "OPENAI_RESPONSES" to zeroPricing(PricingCurrency.USD),
        "OPENAI_RESPONSES_GENERIC" to zeroPricing(PricingCurrency.USD),
        "OPENAI_GENERIC" to zeroPricing(PricingCurrency.USD),
        "ANTHROPIC" to zeroPricing(PricingCurrency.USD),
        "ANTHROPIC_GENERIC" to zeroPricing(PricingCurrency.USD),
        "GOOGLE" to zeroPricing(PricingCurrency.USD),
        "GEMINI_GENERIC" to zeroPricing(PricingCurrency.USD),
        "MISTRAL" to zeroPricing(PricingCurrency.USD),
        "OPENROUTER" to zeroPricing(PricingCurrency.USD),
        "NOUS_PORTAL" to zeroPricing(PricingCurrency.USD),
        "OTHER" to zeroPricing(PricingCurrency.USD),
        "OPENAI_LOCAL" to zeroPricing(PricingCurrency.CNY),
        "DEEPSEEK" to zeroPricing(PricingCurrency.CNY),
        "BAIDU" to zeroPricing(PricingCurrency.CNY),
        "ALIYUN" to zeroPricing(PricingCurrency.CNY),
        "XUNFEI" to zeroPricing(PricingCurrency.CNY),
        "ZHIPU" to zeroPricing(PricingCurrency.CNY),
        "BAICHUAN" to zeroPricing(PricingCurrency.CNY),
        "MOONSHOT" to zeroPricing(PricingCurrency.CNY),
        "SILICONFLOW" to zeroPricing(PricingCurrency.CNY),
        "FOUR_ROUTER" to zeroPricing(PricingCurrency.CNY),
        "INFINIAI" to zeroPricing(PricingCurrency.CNY),
        "ALIPAY_BAILING" to zeroPricing(PricingCurrency.CNY),
        "DOUBAO" to zeroPricing(PricingCurrency.CNY),
        "PPINFRA" to zeroPricing(PricingCurrency.CNY),
        "LMSTUDIO" to zeroPricing(PricingCurrency.CNY),
        "OLLAMA" to zeroPricing(PricingCurrency.CNY),
        "MNN" to zeroPricing(PricingCurrency.CNY),
        "LLAMA_CPP" to zeroPricing(PricingCurrency.CNY),
        "MIMO" to zeroPricing(PricingCurrency.CNY),
        "NOVITA" to zeroPricing(PricingCurrency.USD)
    )

    private fun splitProviderModel(providerModel: String): Pair<String, String> {
        val colonIndex = providerModel.indexOf(':')
        if (colonIndex <= 0) {
            return providerModel.uppercase() to ""
        }
        val provider = providerModel.substring(0, colonIndex).uppercase()
        val model = providerModel.substring(colonIndex + 1)
        return provider to model
    }

    fun isDomesticProvider(provider: String): Boolean {
        return domesticProviders.contains(provider.uppercase())
    }

    fun getDefaultPricing(providerModel: String): ModelPricingDefaults {
        val normalized = PricingCatalogJson.normalize(providerModel)
        val catalog = activeCatalog
        catalog.exact[normalized]?.let { return it }
        val (activeProvider, activeModel) = splitProviderModel(providerModel)
        if (activeModel.isNotBlank()) {
            catalog.aliases["${PricingCatalogJson.normalize(activeProvider)}:${PricingCatalogJson.normalize(activeModel)}"]?.let {
                return it
            }
        }
        scrapedModelDefaults[normalized]?.let { return it }

        val (provider, _) = splitProviderModel(providerModel)

        return providerFallbacks[provider]
            ?: if (isDomesticProvider(provider)) {
                zeroPricing(PricingCurrency.CNY)
            } else {
                zeroPricing(PricingCurrency.USD)
            }
    }

    fun getCurrency(providerModel: String): PricingCurrency {
        return getDefaultPricing(providerModel).currency
    }
}
