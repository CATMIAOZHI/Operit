package com.ai.assistance.operit.util

/**
 * A short excerpt of a message that is quoted back to the model (a reply, a tapback). A long
 * message keeps its start and its end, where a question or an offer usually sits, so the quote
 * stays small but still says which message it was. The full message stays in the chat itself.
 */
object QuoteExcerpt {
    /** Characters kept in a quote; a message up to this length is quoted whole. */
    const val MAX_CHARS = 240

    private val space = Regex("\\s+")

    fun of(text: String, maxChars: Int = MAX_CHARS): String {
        val flat = text.replace(space, " ").trim()
        if (flat.codePointCount(0, flat.length) <= maxChars) return flat
        val half = maxChars / 2
        // Counted in code points so an emoji or a rare character is never cut in half.
        val headEnd = flat.offsetByCodePoints(0, half)
        val tailStart = flat.offsetByCodePoints(flat.length, -half)
        return flat.substring(0, headEnd).trimEnd() + " … " + flat.substring(tailStart).trimStart()
    }
}
