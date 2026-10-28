package com.ai.assistance.operit.data.model

/**
 * Official API names that models.dev only lists as a provider alias of another official model.
 * The pricing generator keeps the same table in
 * `tools/pricing/generate_official_model_pricing_catalog.py`; change both together.
 */
object OfficialModelAliases {
    private val byId = mapOf(
        "deepseek/deepseek-flash" to "deepseek/deepseek-v4.1-flash-1017",
    )

    private val byShortId = byId.entries
        .groupBy({ it.key.substringAfterLast('/') }, { it.value })
        .mapNotNull { (short, targets) -> targets.distinct().singleOrNull()?.let { short to it } }
        .toMap()

    /** The official model ID behind [model], or null when it is not a known alias. */
    fun resolve(model: String): String? {
        val query = normalize(model)
        return byId[query] ?: byShortId[query.substringAfterLast('/')]
    }

    /** Provider model names that are aliases of [officialId], without the owner prefix. */
    fun shortAliasesOf(officialId: String): List<String> {
        val target = normalize(officialId)
        return byId.filterValues { it == target }.keys.map { it.substringAfterLast('/') }
    }

    private fun normalize(value: String): String = value.trim().lowercase().replace('_', '-')
}
