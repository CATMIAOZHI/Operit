package com.ai.assistance.operit.data.pricing

import com.ai.assistance.operit.data.collects.ModelPricingDefaults
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/** Versioned, remotely refreshable model-pricing document. */
@Serializable
data class PricingCatalogDocument(
    val schemaVersion: Int,
    val revision: String,
    val generatedAt: String,
    val entries: List<PricingCatalogEntry>,
)

/** A nullable price is deliberately different from an explicit zero price. */
@Serializable
data class PricingCatalogEntry(
    val provider: String,
    val model: String,
    val billingMode: String,
    val currency: String,
    val input: Double?,
    val cacheRead: Double?,
    val cacheWrite: Double?,
    val output: Double?,
    val perRequest: Double?,
    val aliases: List<String>,
    val sourceUrl: String?,
    val verifiedAt: String?,
)

object PricingCatalogJson {
    const val SCHEMA_VERSION = 1

    val strict = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        coerceInputValues = false
    }

    /** Decode and validate both the document shape and all business invariants. */
    fun parse(raw: String): PricingCatalogDocument {
        val root = strict.parseToJsonElement(raw).jsonObject
        require(root.keys == setOf("schemaVersion", "revision", "generatedAt", "entries")) {
            "pricing catalog has unexpected or missing top-level fields"
        }
        val entries = root["entries"]?.jsonArray
            ?: error("pricing catalog entries must be an array")
        entries.forEachIndexed { index, element ->
            val keys = element.jsonObject.keys
            val expected = setOf(
                "provider", "model", "billingMode", "currency", "input", "cacheRead",
                "cacheWrite", "output", "perRequest", "aliases", "sourceUrl", "verifiedAt",
            )
            require(keys == expected) { "entry[$index] has unexpected or missing fields" }
        }
        val document = strict.decodeFromString<PricingCatalogDocument>(raw)
        validate(document)
        return document
    }

    fun validate(document: PricingCatalogDocument): PricingCatalogDocument {
        require(document.schemaVersion == SCHEMA_VERSION) { "unsupported schemaVersion" }
        require(document.revision.isNotBlank()) { "revision must not be blank" }
        require(runCatching { Instant.parse(document.generatedAt) }.isSuccess) {
            "generatedAt must be an ISO-8601 instant"
        }

        val keys = HashSet<String>(document.entries.size)
        val aliases = HashSet<String>()
        document.entries.forEachIndexed { index, entry ->
            val provider = normalize(entry.provider)
            val model = normalize(entry.model)
            require(provider.isNotEmpty() && model.isNotEmpty()) { "entry[$index] key is blank" }
            require(BillingMode.entries.any { it.name == entry.billingMode }) {
                "entry[$index] has invalid billingMode"
            }
            require(entry.currency == "USD" || entry.currency == "CNY") {
                "entry[$index] has invalid currency"
            }
            val key = "$provider:$model"
            require(keys.add(key)) { "duplicate pricing key: $key" }
            entry.aliases.forEach { alias ->
                val normalizedAlias = normalize(alias)
                require(normalizedAlias.isNotEmpty()) { "entry[$index] has blank alias" }
                require("$provider:$normalizedAlias" !in keys) {
                    "alias collides with pricing key: $provider:$normalizedAlias"
                }
                require(aliases.add("$provider:$normalizedAlias")) {
                    "duplicate pricing alias: $provider:$normalizedAlias"
                }
            }
            listOf(entry.input, entry.cacheRead, entry.cacheWrite, entry.output, entry.perRequest)
                .forEach { value ->
                    require(value == null || (value.isFinite() && value >= 0.0)) {
                        "entry[$index] contains an invalid price"
                    }
                }
            if (entry.billingMode == BillingMode.COUNT.name) {
                require(entry.input == null && entry.cacheRead == null && entry.cacheWrite == null && entry.output == null) {
                    "COUNT entries cannot contain token prices"
                }
            } else {
                require(entry.perRequest == null) { "TOKEN entries cannot contain perRequest" }
            }
            require(entry.sourceUrl == null || entry.sourceUrl.startsWith("https://")) {
                "entry[$index] sourceUrl must be HTTPS"
            }
            require(entry.verifiedAt == null || runCatching { Instant.parse(entry.verifiedAt) }.isSuccess) {
                "entry[$index] verifiedAt must be an ISO-8601 instant"
            }
        }
        val aliasCollision = aliases.firstOrNull { it in keys }
        require(aliasCollision == null) { "pricing alias conflicts with an exact key: $aliasCollision" }
        return document
    }

    fun normalize(value: String): String = value.trim().lowercase().replace(Regex("\\s+"), " ")

    fun entryToDefaults(entry: PricingCatalogEntry): ModelPricingDefaults {
        val billingMode = BillingMode.valueOf(entry.billingMode)
        val currency = if (entry.currency == "CNY") PricingCurrency.CNY else PricingCurrency.USD
        val complete = if (billingMode == BillingMode.COUNT) {
            entry.perRequest != null
        } else {
            entry.input != null && entry.cacheRead != null && entry.output != null
        }
        return ModelPricingDefaults(
            billingMode = billingMode,
            inputPricePerMillion = entry.input ?: 0.0,
            outputPricePerMillion = entry.output ?: 0.0,
            cachedInputPricePerMillion = entry.cacheRead ?: entry.input ?: 0.0,
            pricePerRequest = entry.perRequest ?: 0.0,
            currency = currency,
            known = complete,
            cacheWritePricePerMillion = entry.cacheWrite,
            hasInputPrice = entry.input != null,
            hasCachedInputPrice = entry.cacheRead != null,
            hasOutputPrice = entry.output != null,
            hasPricePerRequest = entry.perRequest != null,
        )
    }
}
