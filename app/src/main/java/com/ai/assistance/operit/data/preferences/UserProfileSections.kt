package com.ai.assistance.operit.data.preferences

/** One document and one approval/version boundary, with three independently editable sections. */
data class UserProfileSections(
    val profile: String = "",
    val preferences: String = "",
    val interactionRules: String = ""
) {
    fun get(section: String): String = when (section) {
        "profile" -> profile
        "preferences" -> preferences
        "interaction_rules" -> interactionRules
        else -> error("Use section=profile/preferences/interaction_rules")
    }

    fun with(section: String, content: String): UserProfileSections = when (section) {
        "profile" -> copy(profile = content)
        "preferences" -> copy(preferences = content)
        "interaction_rules" -> copy(interactionRules = content)
        else -> error("Use section=profile/preferences/interaction_rules")
    }

    fun markdown(): String {
        if (listOf(profile, preferences, interactionRules).all { it.isBlank() }) return ""
        val normalized = copy(profile = profile.trim(), preferences = preferences.trim(),
            interactionRules = interactionRules.trim())
        val bodies = listOf(normalized.profile, normalized.preferences, normalized.interactionRules)
        val text = headings.indices.joinToString("\n\n") { index ->
            "${markers[index]}\n${headings[index]}\n\n${bodies[index]}".trimEnd()
        }
        require(parse(text) == normalized) {
            "Section content must not contain reserved operit:user-section markers"
        }
        return text
    }

    companion object {
        private val headings = listOf("## Profile", "## Preferences", "## Interaction Rules")
        private val markers = listOf("profile", "preferences", "interaction_rules")
            .map { "<!-- operit:user-section:$it -->" }

        /** Unstructured or ambiguous documents remain intact in Profile until an explicit edit. */
        fun parse(markdown: String): UserProfileSections {
            val marked = Regex("(?m)^<!-- operit:user-section:(profile|preferences|interaction_rules) -->\\r?$")
                .findAll(markdown).toList()
            if (marked.isNotEmpty()) {
                if (marked.map { it.value.trimEnd() } != markers ||
                    markdown.take(marked.first().range.first).isNotBlank()) return UserProfileSections(profile = markdown)
                val bodies = marked.mapIndexed { index, match ->
                    val body = markdown.substring(match.range.last + 1,
                        marked.getOrNull(index + 1)?.range?.first ?: markdown.length).trimStart('\r', '\n')
                    if (!body.startsWith(headings[index] + "\n") &&
                        !body.startsWith(headings[index] + "\r\n") && body != headings[index]) {
                        return UserProfileSections(profile = markdown)
                    }
                    body.removePrefix(headings[index]).trim()
                }
                return UserProfileSections(bodies[0], bodies[1], bodies[2])
            }
            val boundaries = mutableListOf<Pair<Int, Int>>()
            var offset = 0
            var fence: Char? = null
            var fenceLength = 0
            markdown.split('\n').forEach { line ->
                val trimmed = line.trim()
                val run = trimmed.takeWhile { it == '`' || it == '~' }
                if (run.length >= 3 && run.all { it == run.first() }) {
                    if (fence == null) {
                        fence = run.first(); fenceLength = run.length
                    } else if (run.first() == fence && run.length >= fenceLength && trimmed == run) {
                        fence = null
                    }
                } else if (fence == null && line.trimEnd() in headings) {
                    boundaries += offset to headings.indexOf(line.trimEnd())
                }
                offset += line.length + 1
            }
            if (boundaries.map { it.second } != listOf(0, 1, 2) ||
                markdown.take(boundaries.firstOrNull()?.first ?: 0).isNotBlank()) {
                return UserProfileSections(profile = markdown)
            }
            val bodies = boundaries.mapIndexed { index, (start, heading) ->
                markdown.substring(start + headings[heading].length,
                    boundaries.getOrNull(index + 1)?.first ?: markdown.length).trim()
            }
            return UserProfileSections(bodies[0], bodies[1], bodies[2])
        }
    }
}
