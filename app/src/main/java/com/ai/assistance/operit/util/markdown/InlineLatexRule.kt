package com.ai.assistance.operit.util.markdown

/**
 * When what the inline splitter took as `$…$` (or `\(…\)`) really is a formula. The splitter
 * decides as it reads, one character at a time; this is the one rule every renderer checks it
 * against afterwards, so a price like "$99.99" or "$20–$30" is shown as the text it is.
 */
object InlineLatexRule {
    /** [text] is the splitter's group, delimiters included; [next] is the character right after it. */
    fun isFormula(text: String, next: Char?): Boolean {
        if (text.startsWith("\\(")) return text.length >= 4 && text.endsWith("\\)")
        if (text.length < 3 || !text.startsWith("$") || !text.endsWith("$") || text.startsWith("$$")) return false
        // "\$" is a dollar sign, so the closing '$' must not be escaped.
        if (escaped(text, text.length - 1)) return false
        val inner = text.substring(1, text.length - 1)
        // Right after the opening and right before the closing '$' comes the formula, not a space.
        if (inner.first().isWhitespace() || inner.last().isWhitespace() || '\n' in inner) return false
        // A '$' inside means the group ran past a '$' that did not close it.
        if (inner.indices.any { inner[it] == '$' && !escaped(inner, it) }) return false
        // "$20–$30": a closing '$' is never followed by a digit.
        return next == null || !next.isDigit()
    }

    /** Whether the character at [index] follows an odd number of backslashes. */
    private fun escaped(text: String, index: Int): Boolean {
        var count = 0
        var i = index - 1
        while (i >= 0 && text[i] == '\\') {
            count++
            i--
        }
        return count % 2 == 1
    }
}
