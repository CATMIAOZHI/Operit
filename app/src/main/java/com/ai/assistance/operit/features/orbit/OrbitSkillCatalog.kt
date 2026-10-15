package com.ai.assistance.operit.features.orbit

/** Only metadata is indexed; skill bodies and attachments are loaded by use_package on demand. */
internal object OrbitSkillCatalog {
    const val PREVIEW_SIZE = 12
    const val MAX_PAGE_SIZE = 20
    data class Entry(val name: String, val description: String)
    data class Page(val entries: List<Entry>, val total: Int, val nextOffset: Int?)

    fun page(catalog: Map<String, String>, query: String = "", offset: Int = 0,
        limit: Int = MAX_PAGE_SIZE): Page {
        require(query.length <= 200 && offset >= 0 && limit in 1..MAX_PAGE_SIZE)
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val matches = catalog.entries.filter { entry ->
            terms.all { entry.key.contains(it, true) || entry.value.contains(it, true) }
        }.sortedBy { it.key }
        val entries = matches.drop(offset).take(limit).map {
            Entry(it.key, it.value.replace(Regex("\\s+"), " ").trim().take(240))
        }
        val next = offset.toLong() + entries.size
        return Page(entries, matches.size, next.takeIf { it < matches.size }?.toInt())
    }
}
