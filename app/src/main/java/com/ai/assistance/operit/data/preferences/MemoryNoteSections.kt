package com.ai.assistance.operit.data.preferences

/**
 * An Orbit's memory.md, sorted into four fixed sections. Older notes that predate the sections, or
 * text a manual edit left outside them, stay in [unsorted] until a review moves them, so nothing is
 * ever lost by reading or re-rendering a document.
 *
 * The marker lines are what the app writes; a known heading in either language also starts its
 * section, so a document whose marker a hand edit lost is still sorted by what the reader sees.
 */
data class MemoryNoteSections(
    val facts: String = "",
    val preferences: String = "",
    val decisions: String = "",
    val commitments: String = "",
    val unsorted: String = "",
) {
    fun get(section: String): String = when (section) {
        "facts" -> facts
        "preferences" -> preferences
        "decisions" -> decisions
        "commitments" -> commitments
        UNSORTED -> unsorted
        else -> throw IllegalArgumentException(SECTION_HINT)
    }

    fun with(section: String, content: String): MemoryNoteSections = when (section) {
        "facts" -> copy(facts = content)
        "preferences" -> copy(preferences = content)
        "decisions" -> copy(decisions = content)
        "commitments" -> copy(commitments = content)
        UNSORTED -> copy(unsorted = content)
        else -> throw IllegalArgumentException(SECTION_HINT)
    }

    fun bodies(): List<Pair<String, String>> = ALL.map { it to get(it) }

    /** The stored form. [skeleton] also renders a document with nothing in it, for an editor. */
    fun markdown(english: Boolean, skeleton: Boolean = false): String {
        if (!skeleton && ALL.all { get(it).isBlank() }) return ""
        val headings = if (english) HEADINGS_EN else HEADINGS_ZH
        return ALL.filter { it != UNSORTED || unsorted.isNotBlank() }.joinToString("\n\n") { key ->
            "${marker(key)}\n${headings.getValue(key)}\n\n${clean(get(key))}".trimEnd()
        }
    }

    companion object {
        /** The sections a writer may add to. Unsorted only ever shrinks. */
        val WRITABLE = listOf("facts", "preferences", "decisions", "commitments")
        const val UNSORTED = "unsorted"
        private val ALL = WRITABLE + UNSORTED
        const val SECTION_HINT =
            "memory.md is sorted into sections: pass section=facts, preferences, decisions or commitments"

        private val HEADINGS_ZH = mapOf("facts" to "## 事实", "preferences" to "## 偏好", "decisions" to "## 决定",
            "commitments" to "## 承诺", UNSORTED to "## 未分类（旧内容）")
        private val HEADINGS_EN = mapOf("facts" to "## Facts", "preferences" to "## Preferences",
            "decisions" to "## Decisions", "commitments" to "## Commitments", UNSORTED to "## Unsorted (older notes)")
        private val MARKER = Regex("(?m)^<!-- operit:memory-section:([a-z_]+) -->[ \\t]*$")
        private val ANY_MARKER_LINE = Regex("(?m)^[ \\t]*<!-- operit:(memory|user)-section:[a-z_]+ -->[ \\t]*\\r?\\n?")

        private fun marker(key: String) = "<!-- operit:memory-section:$key -->"
        private fun headingKey(line: String): String? = line.trimEnd().let { text ->
            ALL.firstOrNull { HEADINGS_ZH[it] == text || HEADINGS_EN[it] == text }
        }
        /**
         * A body never carries a marker line, and a code block it opens is closed at its end, so the
         * headings after it still start their sections when an editor shows the text without markers.
         */
        private fun clean(body: String): String {
            val text = body.replace("\r\n", "\n").replace(MARKER, "").trim()
            val fence = Fence()
            text.split('\n').forEach { fence.feed(it) }
            return fence.open?.let { "$text\n$it" } ?: text
        }

        /** Tracks whether a line is inside a ``` or ~~~ code block. */
        private class Fence {
            private var char: Char? = null
            private var length = 0
            val inside: Boolean get() = char != null
            /** The line that would close the block still open, or null. */
            val open: String? get() = char?.let { c -> c.toString().repeat(length) }
            fun reset() { char = null }
            fun feed(line: String) {
                val trimmed = line.trim()
                val run = trimmed.takeWhile { it == '`' || it == '~' }
                if (run.length < 3 || !run.all { it == run.first() }) return
                if (char == null) { char = run.first(); length = run.length }
                else if (run.first() == char && run.length >= length && trimmed == run) char = null
            }
        }

        /** The markers are for the app; readers and the prompt only need the headings. */
        fun withoutMarkers(markdown: String): String = markdown.replace(ANY_MARKER_LINE, "")

        /** What an editor shows: every section heading, even an empty one, and no marker lines. */
        fun editable(markdown: String, english: Boolean): String =
            withoutMarkers(parse(markdown).markdown(english, skeleton = true))

        /**
         * One entry as it will be stored inside a section: a marker or a section heading in it
         * would start another section when the document is read back, so both are dropped here,
         * before an add is compared with what the section already holds.
         */
        fun cleanEntry(text: String): String = clean(text.replace("\r\n", "\n").lines()
            .filterNot { MARKER.matches(it.trim()) || headingKey(it.trim()) != null }
            .joinToString("\n"))

        fun parse(markdown: String): MemoryNoteSections {
            val text = markdown.replace("\r\n", "\n")
            val parts = linkedMapOf<String, MutableList<String>>()
            fun add(key: String, body: String) {
                if (body.isNotBlank()) parts.getOrPut(key) { mutableListOf() } += body.trim()
            }
            // A marker line or a known heading (outside code) starts its section; text before the
            // first of them, or under a marker this version does not know, is unsorted. Bodies never
            // hold a marker line, so a marker always ends a section, even after an unclosed fence.
            var key = UNSORTED
            val current = StringBuilder()
            val fence = Fence()
            text.split('\n').forEach { line ->
                val marked = MARKER.matchEntire(line)?.groupValues?.get(1)
                if (marked != null) fence.reset()
                val heading = if (!fence.inside && marked == null) headingKey(line) else null
                if (marked == null) fence.feed(line)
                val next = marked?.let { k -> k.takeIf { it in ALL } ?: UNSORTED } ?: heading
                if (next != null) {
                    add(key, current.toString()); current.clear(); key = next
                } else current.append(line).append('\n')
            }
            add(key, current.toString())
            fun body(key: String) = parts[key].orEmpty().joinToString("\n\n")
            return MemoryNoteSections(body("facts"), body("preferences"), body("decisions"),
                body("commitments"), body(UNSORTED))
        }
    }
}
