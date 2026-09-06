package com.ai.assistance.operit.data.pricing

import com.ai.assistance.operit.data.collects.ModelPricingDefaults
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Match model identities independently of the user's API connection/provider. */
class OfficialPricingIndex(entries: List<PricingCatalogEntry>) {
    private val byId = entries.groupBy { normalize(it.model) }
    private val byShortId = entries.groupBy { normalize(it.model.substringAfterLast('/')) }
    private val byAlias = entries.flatMap { entry ->
        entry.aliases.map { normalize(it) to entry }
    }.groupBy({ it.first }, { it.second })
    private val routes = entries.groupBy { normalize(it.model).replace('/', '-') }

    fun lookup(model: String): ModelPricingDefaults? {
        val query = normalize(model)
        byId[query]?.let { return unique(it) }
        val short = query.substringAfterLast('/')
        byShortId[short]?.let { return unique(it) }
        val routed = routes.filterKeys { short == it || short.endsWith("-$it") }
            .values.flatten()
        if (routed.isNotEmpty()) return unique(routed)
        return byAlias[query]?.let(::unique)
    }

    private fun unique(entries: List<PricingCatalogEntry>): ModelPricingDefaults? =
        entries.distinctBy { normalize(it.model) }.singleOrNull()?.let(PricingCatalogJson::entryToDefaults)

    companion object {
        fun normalize(value: String): String = value.trim().lowercase().replace('_', '-')
    }
}

/** models.json supplies official identities; api.json supplies their official base text prices. */
object ModelsDevPricingCatalog {
    const val MODELS_URL = "https://models.dev/models.json"
    const val PRICES_URL = "https://models.dev/api.json"
    const val SOURCES_ASSET = "pricing/official_price_sources_v1.json"

    fun build(
        modelsJson: String,
        pricesJson: String,
        sourcesJson: String,
        generatedAt: String = Instant.now().toString(),
    ): PricingCatalogDocument {
        val models = Json.parseToJsonElement(modelsJson).jsonObject
        val providers = Json.parseToJsonElement(pricesJson).jsonObject
        val sources = Json.parseToJsonElement(sourcesJson).jsonObject
        val names = models.mapNotNull { (id, element) ->
            (element as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }?.let { id to it }
        }.toMap()
        val nameCounts = names.values.groupingBy(OfficialPricingIndex::normalize).eachCount()
        val modelIds = models.keys.map(OfficialPricingIndex::normalize).toSet()
        val entries = models.mapNotNull { (officialId, _) ->
            val owner = officialId.substringBefore('/')
            val source = sources[owner]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rows = (providers[source] as? JsonObject)?.get("models") as? JsonObject
                ?: return@mapNotNull null
            val short = OfficialPricingIndex.normalize(officialId.substringAfterLast('/'))
            val candidates = rows.mapNotNull candidate@{ (key, element) ->
                val row = element as? JsonObject ?: return@candidate null
                val id = row["id"]?.jsonPrimitive?.contentOrNull ?: key
                if (listOf(key, id, id.substringAfterLast('/')).none {
                        OfficialPricingIndex.normalize(it) == short
                    }) return@candidate null
                row
            }
            val row = candidates.singleOrNull() ?: return@mapNotNull null
            val cost = row["cost"] as? JsonObject ?: return@mapNotNull null
            val input = price(cost, "input") ?: return@mapNotNull null
            val output = price(cost, "output") ?: return@mapNotNull null
            PricingCatalogEntry(
                provider = owner,
                model = officialId,
                billingMode = "TOKEN",
                currency = "USD",
                input = input,
                cacheRead = price(cost, "cache_read") ?: input,
                cacheWrite = price(cost, "cache_write"),
                output = output,
                perRequest = null,
                aliases = listOfNotNull(names[officialId]?.takeIf {
                    val normalized = OfficialPricingIndex.normalize(it)
                    nameCounts[normalized] == 1 && normalized !in modelIds && normalized != short
                }),
                sourceUrl = PRICES_URL,
                verifiedAt = null,
                contextTiers = tiers(cost),
            )
        }.sortedBy { it.model }
        require(entries.isNotEmpty()) { "models.dev contains no matching official prices" }
        // A content revision stays stable across downloads of unchanged prices.
        val canonical = PricingCatalogJson.strict.encodeToString(entries)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        return PricingCatalogJson.validate(
            PricingCatalogDocument(1, "official-$digest", generatedAt, entries)
        )
    }

    private fun price(cost: JsonObject, key: String): Double? =
        cost[key]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 }

    private fun tiers(cost: JsonObject): List<ContextPriceTier> {
        val tiers = (cost["tiers"] as? JsonArray).orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val tier = row["tier"] as? JsonObject ?: return@mapNotNull null
            if (tier["type"]?.jsonPrimitive?.contentOrNull != "context") return@mapNotNull null
            val threshold = requireNotNull(tier["size"]?.jsonPrimitive?.longOrNull)
            contextTier(threshold, row)
        }
        if (tiers.isNotEmpty()) return tiers.sortedBy { it.minInputTokens }
        return (cost["context_over_200k"] as? JsonObject)?.let {
            listOf(contextTier(200_000, it))
        }.orEmpty()
    }

    private fun contextTier(threshold: Long, row: JsonObject): ContextPriceTier {
        val input = requireNotNull(price(row, "input")) { "context tier missing input price" }
        return ContextPriceTier(
            threshold, input, requireNotNull(price(row, "output")) { "context tier missing output price" },
            price(row, "cache_read") ?: input, price(row, "cache_write"),
        )
    }
}
