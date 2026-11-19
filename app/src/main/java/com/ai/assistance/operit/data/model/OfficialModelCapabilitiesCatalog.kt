package com.ai.assistance.operit.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class OfficialModelCapabilities(
    val officialModelId: String,
    val displayName: String,
    val family: String,
    val capabilities: ModelMultimodalCapabilities,
    /** The context window and longest reply the catalog gives; null when it gives neither. */
    val limits: ModelLimits? = null,
)

sealed interface OfficialModelCapabilitiesLookup {
    data class Match(val model: OfficialModelCapabilities) : OfficialModelCapabilitiesLookup

    data class Candidates(val models: List<OfficialModelCapabilities>) :
        OfficialModelCapabilitiesLookup

    data object NotFound : OfficialModelCapabilitiesLookup
}

class OfficialModelCapabilitiesCatalog private constructor(
    val models: List<OfficialModelCapabilities>,
) {
    private val modelsById = models.groupBy { normalize(it.officialModelId) }
    private val modelsByShortId =
        models.groupBy { normalize(it.officialModelId.substringAfterLast('/')) }
    private val modelsByDisplayName = models.groupBy { normalize(it.displayName) }

    private val modelsByRouteQualifiedId =
        models.groupBy { normalize(it.officialModelId).replace('/', '-') }
    private val catalogOrder = models.withIndex().associate { (index, model) -> model to index }

    /** False for a catalog from before limits were kept (an older cache). */
    val hasLimits: Boolean = models.any { it.limits != null }

    fun matchAll(
        configuredModelNames: List<String>,
    ): Map<String, ModelMultimodalCapabilities> =
        matchEntries(configuredModelNames).mapValues { it.value.capabilities }

    /** The catalog entry of each configured model that matches exactly one. */
    fun matchEntries(
        configuredModelNames: List<String>,
    ): Map<String, OfficialModelCapabilities> =
        configuredModelNames.mapNotNull { configuredModelName ->
            when (val lookup = lookup(configuredModelName, suggest = false)) {
                is OfficialModelCapabilitiesLookup.Match ->
                    configuredModelName to lookup.model
                is OfficialModelCapabilitiesLookup.Candidates,
                OfficialModelCapabilitiesLookup.NotFound -> null
            }
        }.toMap()

    /** With [suggest] off, a model with no match is [OfficialModelCapabilitiesLookup.NotFound] at once. */
    fun lookup(configuredModelName: String, suggest: Boolean = true): OfficialModelCapabilitiesLookup {
        val normalizedQuery = normalize(configuredModelName)
        if (normalizedQuery.isEmpty()) {
            return OfficialModelCapabilitiesLookup.NotFound
        }

        uniqueOrCandidates(modelsById[normalizedQuery])?.let { return it }

        val routedModelId = normalizedQuery.substringAfterLast('/')
        uniqueOrCandidates(modelsByShortId[routedModelId])?.let { return it }

        OfficialModelAliases.resolve(normalizedQuery)?.let { officialId ->
            uniqueOrCandidates(modelsById[normalize(officialId)])?.let { return it }
        }

        // Zen's free-lane IDs name the same base model with a pricing suffix.
        // Only use this alias for an exact base ID, never for a fuzzy capability guess.
        val freeBaseId = routedModelId.removeSuffix("-contributor-free").removeSuffix("-free")
        if (freeBaseId != routedModelId) {
            uniqueOrCandidates(modelsByShortId[freeBaseId])?.let { return it }
        }

        // The ID itself, or what follows any '-' in it, named as a route-qualified catalog ID.
        val suffixMatches =
            buildList {
                modelsByRouteQualifiedId[routedModelId]?.let(::addAll)
                routedModelId.forEachIndexed { index, char ->
                    if (char == '-') modelsByRouteQualifiedId[routedModelId.substring(index + 1)]?.let(::addAll)
                }
            }.distinct().sortedBy { catalogOrder.getValue(it) }
        uniqueOrCandidates(suffixMatches)?.let { return it }

        uniqueOrCandidates(modelsByDisplayName[normalizedQuery])?.let { return it }
        if (!suggest) return OfficialModelCapabilitiesLookup.NotFound

        val suggestions =
            models
                .mapNotNull { model ->
                    val officialId = normalize(model.officialModelId)
                    val shortId = normalize(model.officialModelId.substringAfterLast('/'))
                    val displayName = normalize(model.displayName)
                    val score =
                        when {
                            shortId.startsWith(routedModelId) ||
                                routedModelId.startsWith(shortId) -> 0
                            officialId.contains(normalizedQuery) ||
                                normalizedQuery.contains(officialId) -> 1
                            shortId.contains(routedModelId) ||
                                routedModelId.contains(shortId) -> 2
                            displayName.contains(normalizedQuery) ||
                                normalizedQuery.contains(displayName) -> 3
                            else -> return@mapNotNull null
                        }
                    score to model
                }
                .sortedWith(
                    compareBy<Pair<Int, OfficialModelCapabilities>> { it.first }
                        .thenBy { it.second.officialModelId.length }
                        .thenBy { it.second.officialModelId },
                )
                .map { it.second }
                .distinctBy { normalize(it.officialModelId) }
                .take(MAX_SUGGESTIONS)

        return if (suggestions.isEmpty()) {
            OfficialModelCapabilitiesLookup.NotFound
        } else {
            OfficialModelCapabilitiesLookup.Candidates(suggestions)
        }
    }

    private fun uniqueOrCandidates(
        candidates: List<OfficialModelCapabilities>?,
    ): OfficialModelCapabilitiesLookup? {
        val distinctCandidates =
            candidates.orEmpty().distinctBy { it.officialModelId }
        return when (distinctCandidates.size) {
            0 -> null
            1 -> OfficialModelCapabilitiesLookup.Match(distinctCandidates.single())
            else -> OfficialModelCapabilitiesLookup.Candidates(distinctCandidates)
        }
    }

    companion object {
        private const val MAX_SUGGESTIONS = 8
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(rawJson: String): OfficialModelCapabilitiesCatalog {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val models =
                root.mapNotNull { (officialModelId, element) ->
                    val modelObject = element.jsonObject
                    val modalities = modelObject["modalities"]?.jsonObject ?: return@mapNotNull null
                    val inputs = modalities["input"] as? JsonArray ?: return@mapNotNull null
                    val inputModalities =
                        inputs.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                    OfficialModelCapabilities(
                        officialModelId = officialModelId,
                        displayName =
                            modelObject["name"]?.jsonPrimitive?.contentOrNull
                                ?: officialModelId,
                        family = modelObject["family"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        capabilities =
                            ModelMultimodalCapabilities(
                                image = "image" in inputModalities,
                                audio = "audio" in inputModalities,
                                video = "video" in inputModalities,
                            ),
                        limits = (modelObject["limit"] as? JsonObject)?.let { limit ->
                            fun tokens(key: String): Int? = (limit[key] as? JsonPrimitive)?.longOrNull
                                ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
                            ModelLimits(tokens("context"), tokens("output"))
                                .takeIf { it.contextTokens != null || it.outputTokens != null }
                        },
                    )
                }
            require(models.isNotEmpty()) { "Official model catalog contains no usable models" }
            return OfficialModelCapabilitiesCatalog(models)
        }

        private fun normalize(value: String): String =
            value.trim().lowercase().replace('_', '-')
    }
}
