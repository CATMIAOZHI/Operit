package com.ai.assistance.operit.util

/**
 * Immutable text backed by shared pieces. Taking a snapshot copies references, not the whole turn.
 * CharSequence readers (notably permission review) need not join the pieces into a large String.
 */
class SegmentedText(parts: List<String>) : CharSequence {
    private val parts = parts.filter { it.isNotEmpty() }
    private val ends = IntArray(this.parts.size)
    override val length: Int

    init {
        var total = 0
        this.parts.forEachIndexed { index, part ->
            total = Math.addExact(total, part.length)
            ends[index] = total
        }
        length = total
    }

    override fun get(index: Int): Char {
        require(index in 0 until length)
        val found = ends.binarySearch(index + 1)
        val part = if (found >= 0) found else -found - 1
        return parts[part][index - if (part == 0) 0 else ends[part - 1]]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        require(startIndex in 0..endIndex && endIndex <= length)
        val result = StringBuilder(endIndex - startIndex)
        var start = 0
        parts.forEach { part ->
            val end = start + part.length
            if (start < endIndex && end > startIndex) {
                result.append(part, (startIndex - start).coerceAtLeast(0),
                    (endIndex - start).coerceAtMost(part.length))
            }
            start = end
        }
        return result.toString()
    }

    override fun toString(): String {
        if (parts.size == 1) return parts[0]
        return buildString(length) { parts.forEach { append(it) } }
    }

    internal fun appendPartsTo(target: MutableList<String>) {
        target.addAll(parts)
    }

    companion object {
        fun join(parts: List<SegmentedText>, separator: String): SegmentedText {
            val pieces = ArrayList<String>()
            parts.forEachIndexed { index, part ->
                if (index > 0) pieces.add(separator)
                part.appendPartsTo(pieces)
            }
            return SegmentedText(pieces)
        }
    }
}

/** The mutable tail is bounded; snapshots seal it and share already sealed pieces. */
class ChunkedTextBuffer {
    private val pieces = ArrayList<String>()
    private var tail = StringBuilder()
    var length: Int = 0
        private set

    fun append(content: String) {
        length = Math.addExact(length, content.length)
        var offset = 0
        while (offset < content.length) {
            val end = minOf(content.length, offset + CHUNK_SIZE - tail.length)
            tail.append(content, offset, end)
            offset = end
            if (tail.length == CHUNK_SIZE) seal()
        }
    }

    fun replace(content: String) {
        pieces.clear()
        tail = StringBuilder()
        length = 0
        append(content)
    }

    fun snapshot(): SegmentedText {
        seal()
        return SegmentedText(pieces)
    }

    private fun seal() {
        if (tail.isEmpty()) return
        pieces.add(tail.toString())
        tail = StringBuilder()
    }

    companion object {
        private const val CHUNK_SIZE = 16 * 1024
    }
}
