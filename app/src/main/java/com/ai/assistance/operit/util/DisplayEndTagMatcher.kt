package com.ai.assistance.operit.util

internal enum class DisplayEndMatchResult {
    MATCH,
    IN_PROGRESS,
    NO_MATCH,
}

/**
 * Incrementally recognizes the closing grammar shared by display-only XML blocks.
 *
 * Think and thinking are one family, matching is case-insensitive, and whitespace is accepted
 * between the closing name and `>`. Search uses the same whitespace/case rules but remains its own
 * family.
 */
internal class DisplayEndTagMatcher(private val validNames: Set<String>) {
    private var phase = 0
    private val matchedName = StringBuilder()
    private var candidateClosing = false
    private var candidateQuote: Char? = null
    private var lastNonWhitespace: Char? = null
    private val familyStack = mutableListOf(rootFamily())

    var candidateLength: Int = 0
        private set

    var lastMatchLength: Int = 0
        private set

    fun processChar(char: Char): DisplayEndMatchResult {
        lastMatchLength = 0
        return when (phase) {
            0 -> if (char == '<') {
                phase = 1
                candidateLength = 1
                DisplayEndMatchResult.IN_PROGRESS
            } else {
                DisplayEndMatchResult.NO_MATCH
            }
            1 ->
                when {
                    char == '/' -> {
                        candidateClosing = true
                        phase = 2
                        matchedName.clear()
                        candidateLength += 1
                        DisplayEndMatchResult.IN_PROGRESS
                    }
                    char.isLetter() -> {
                        candidateClosing = false
                        phase = 2
                        matchedName.clear()
                        matchedName.append(char.lowercaseChar())
                        candidateLength += 1
                        DisplayEndMatchResult.IN_PROGRESS
                    }
                    else -> restartFrom(char)
                }
            2 -> {
                when {
                    char.isLetterOrDigit() || char == '_' || char == '-' || char == '.' || char == ':' -> {
                        matchedName.append(char.lowercaseChar())
                        candidateLength += 1
                        DisplayEndMatchResult.IN_PROGRESS
                    }
                    displayTagFamily(matchedName.toString()) == null -> restartFrom(char)
                    char.isWhitespace() -> {
                        phase = 3
                        candidateLength += 1
                        DisplayEndMatchResult.IN_PROGRESS
                    }
                    !candidateClosing && char == '/' -> {
                        phase = 3
                        lastNonWhitespace = '/'
                        candidateLength += 1
                        DisplayEndMatchResult.IN_PROGRESS
                    }
                    char == '>' -> {
                        candidateLength += 1
                        completeCandidate()
                    }
                    else -> restartFrom(char)
                }
            }
            else -> {
                candidateLength += 1
                if (candidateQuote != null) {
                    if (!char.isWhitespace()) lastNonWhitespace = char
                    if (char == candidateQuote) candidateQuote = null
                    DisplayEndMatchResult.IN_PROGRESS
                } else {
                    when {
                        !candidateClosing && (char == '\'' || char == '"') -> {
                            lastNonWhitespace = char
                            candidateQuote = char
                            DisplayEndMatchResult.IN_PROGRESS
                        }
                        char == '<' -> restartFrom(char)
                        char == '>' -> completeCandidate()
                        else -> {
                            if (!char.isWhitespace()) lastNonWhitespace = char
                            DisplayEndMatchResult.IN_PROGRESS
                        }
                    }
                }
            }
        }
    }

    fun reset() {
        resetCandidate()
        lastMatchLength = 0
        familyStack.clear()
        familyStack.add(rootFamily())
    }

    private fun completeCandidate(): DisplayEndMatchResult {
        val completedLength = candidateLength
        val family = displayTagFamily(matchedName.toString())
        val result =
            when {
                family == null -> DisplayEndMatchResult.NO_MATCH
                candidateClosing && lastNonWhitespace == null && familyStack.lastOrNull() == family -> {
                    familyStack.removeAt(familyStack.lastIndex)
                    if (familyStack.isEmpty()) {
                        lastMatchLength = completedLength
                        DisplayEndMatchResult.MATCH
                    } else {
                        DisplayEndMatchResult.NO_MATCH
                    }
                }
                !candidateClosing && lastNonWhitespace != '/' -> {
                    familyStack.add(family)
                    DisplayEndMatchResult.NO_MATCH
                }
                else -> DisplayEndMatchResult.NO_MATCH
            }
        resetCandidate()
        return result
    }

    private fun resetCandidate() {
        phase = 0
        matchedName.clear()
        candidateClosing = false
        candidateQuote = null
        lastNonWhitespace = null
        candidateLength = 0
    }

    private fun restartFrom(char: Char): DisplayEndMatchResult {
        resetCandidate()
        return if (char == '<') {
            phase = 1
            candidateLength = 1
            DisplayEndMatchResult.IN_PROGRESS
        } else {
            DisplayEndMatchResult.NO_MATCH
        }
    }

    private fun rootFamily(): String =
        validNames.firstNotNullOfOrNull(::displayTagFamily)
            ?: error("DisplayEndTagMatcher requires a display tag name")
}

private fun displayTagFamily(tagName: String): String? =
    when (tagName.lowercase()) {
        "think", "thinking" -> "think"
        "search" -> "search"
        else -> null
    }

internal fun displayEndTagNames(tagName: String): Set<String>? =
    when (displayTagFamily(tagName)) {
        "think" -> setOf("think", "thinking")
        "search" -> setOf("search")
        else -> null
    }

internal fun findDisplayEndTagRange(
    content: String,
    tagName: String,
    fromIndex: Int = 0,
): IntRange? {
    val validNames = displayEndTagNames(tagName) ?: return null
    val matcher = DisplayEndTagMatcher(validNames)
    for (index in fromIndex.coerceAtLeast(0) until content.length) {
        if (matcher.processChar(content[index]) == DisplayEndMatchResult.MATCH) {
            val start = index - matcher.lastMatchLength + 1
            return start..index
        }
    }
    return null
}
